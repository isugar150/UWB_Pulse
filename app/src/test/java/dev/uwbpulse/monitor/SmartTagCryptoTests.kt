package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.data.SmartTagCrypto
import org.junit.Assert.*
import org.junit.Test

class SmartTagCryptoTests {
    private val secret = ByteArray(16) { it.toByte() }
    private fun own() = ByteArray(16) { (it + 16).toByte() }
    private fun tag() = ByteArray(16) { (it + 32).toByte() }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun cloudSharedSecretUsesFirst16BytesForOwnerMaster() {
        val full = ByteArray(32) { it.toByte() }
        assertArrayEquals(secret,SmartTagCrypto.ownerMasterSecret(full))
        assertArrayEquals(secret,SmartTagCrypto.ownerMasterSecret(secret))
        assertThrows(IllegalArgumentException::class.java) { SmartTagCrypto.ownerMasterSecret(ByteArray(8)) }
        assertEquals(31,full.last().toInt()) // caller's encrypted-vault material is not mutated
    }
    @Test fun kdfAndMutualAuthenticationMatchIndependentOpenSslVectors() {
        assertArrayEquals(hex("f0e58cd5cd375f78094c304cb668aed4"),SmartTagCrypto.derive(secret,"bleAuthentication".toByteArray()))
        assertArrayEquals(hex("c4496170d9f196b3dc78bbca5cb1742e"),SmartTagCrypto.derive(secret,tag()))
        SmartTagCrypto(secret,own()).use { crypto ->
            assertArrayEquals(hex("d19bd62370e4ee1ceaf8571a7ac088a3"),crypto.phoneProof(tag()))
            crypto.verifyTagProof(hex("32c7f4d27b99fad38c322b137c1b42dd"))
            val first = crypto.encryptCommand(byteArrayOf(1))
            assertArrayEquals(hex("b41b4bc54182aabe628836bde17983b5"),first)
            assertArrayEquals(first,crypto.encryptCommand(byteArrayOf(1))) // busy retries preserve counter
            crypto.commandAcknowledged()
            assertArrayEquals(hex("6b8544cdc085d37e6c8bd45c21b1f5f9"),crypto.encryptCommand(byteArrayOf(0)))
        }
    }
    @Test fun wrongProofNeverEnablesCommandsAndCannotBeRetriedInSameSession() {
        SmartTagCrypto(secret,own()).use { crypto ->
            crypto.phoneProof(tag())
            assertThrows(IllegalStateException::class.java) { crypto.verifyTagProof(ByteArray(16)) }
            assertThrows(IllegalStateException::class.java) { crypto.encryptCommand(byteArrayOf(1)) }
            assertThrows(IllegalStateException::class.java) { crypto.verifyTagProof(hex("32c7f4d27b99fad38c322b137c1b42dd")) }
        }
    }
    @Test fun reflectionAndUnauthenticatedCommandsRejected() {
        SmartTagCrypto(secret,own()).use { crypto ->
            assertThrows(IllegalStateException::class.java) { crypto.phoneProof(own()) }
            assertThrows(IllegalStateException::class.java) { crypto.encryptCommand(byteArrayOf(1)) }
        }
    }
    @Test fun ownerAdvertisementSignatureMatchesAndTamperingFails() {
        val advertisement = hex("000102030405060708090a0b0c0d0e0f51efcc3b")
        assertTrue(SmartTagCrypto.matchesAdvertisement(secret,own(),advertisement))
        advertisement[5] = (advertisement[5].toInt() xor 1).toByte()
        assertFalse(SmartTagCrypto.matchesAdvertisement(secret,own(),advertisement))
        assertFalse(SmartTagCrypto.matchesAdvertisement(secret,own(),ByteArray(12)))
    }
}
