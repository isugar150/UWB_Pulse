/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Kieron Quinn's uTag UwbConfig.kt and LengthByteArray.kt.
 * Upstream commit 6152f89b02d41476cbf923fb411e6a62a1303881.
 * Changes: standalone serializer, explicit FiRa defaults, SecureRandom, input validation.
 */
package dev.uwbpulse.monitor.data

import java.nio.ByteBuffer
import java.security.SecureRandom

class SmartTagSessionConfig(
    val sessionId: Int,
    val peerAddress: ByteArray,
    val preamble: Int,
) {
    init { require(sessionId != 0); require(peerAddress.size == 2); require(preamble in 9..12) }
    val channel = 9
    val sessionKey = ByteArray(8) // Legacy static STS as observed by uTag, NOT Samsung owner credentials.
    fun payload(localAddress: ByteArray, rangingIntervalMillis: Int = 240): ByteArray {
        require(localAddress.size == 2)
        require(rangingIntervalMillis in 50..2_000)
        val bytes = ByteArray(35)
        fun bits(index: Int, value: Int, length: Int) {
            repeat(length) { bit ->
                val offset = index + bit
                val mask = 1 shl (offset % 8)
                bytes[offset / 8] = (if (value and (1 shl bit) == 0)
                    bytes[offset / 8].toInt() and mask.inv() else bytes[offset / 8].toInt() or mask).toByte()
            }
        }
        bits(8, 1, 1); bits(9, 1, 1); bits(10, 1, 1); bits(11, 1, 1)
        bits(12, 2, 2) // DS-TWR deferred
        bits(22, 0, 2) // unicast, overlapped by static STS/key rotation as upstream
        bits(16, 0, 2); bits(18, 3, 2); bits(20, 0, 2)
        bits(22, 0, 1); bits(23, 0, 1); bits(24, 0, 2)
        bits(26, 3, 2); bits(28, 1, 1); bits(29, 0, 1)
        bits(30, 2, 2); bits(32, 1, 2); bits(34, 1, 1)
        ByteBuffer.wrap(bytes, 5, 4).putInt(sessionId)
        peerAddress.copyInto(bytes, 9)
        localAddress.copyInto(bytes, 11)
        bytes[13] = channel.toByte()
        ByteBuffer.wrap(bytes, 14, 2).putShort(2000) // Tag encoding differs from FiRa RSTU 2400.
        ByteBuffer.wrap(bytes, 16, 2).putShort(rangingIntervalMillis.toShort()) // ranging interval ms; 240 matches NORMAL
        bytes[18] = 3; bytes[19] = preamble.toByte(); bytes[20] = 1; bytes[21] = 5
        bytes[30] = 6 // slots per ranging round
        return bytes
    }
    companion object {
        fun create(): SmartTagSessionConfig {
            val random = SecureRandom()
            var id: Int
            do { id = random.nextInt() } while (id == 0)
            val address = ByteArray(2)
            do { random.nextBytes(address) } while (address.all { it == 0.toByte() })
            return SmartTagSessionConfig(id, address, random.nextInt(4) + 9)
        }
    }
}
