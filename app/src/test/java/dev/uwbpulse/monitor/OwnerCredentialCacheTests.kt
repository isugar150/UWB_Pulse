package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.data.auth.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class OwnerCredentialCacheTests {
    private fun key(value: Byte) = OwnerKey("synthetic-tag",Base64.getUrlEncoder().encodeToString(ByteArray(32) { value }),Base64.getUrlEncoder().encodeToString(ByteArray(16) { 1 }))

    @Test fun returnedSecretsAreIndependentAndClearOnClose() {
        val cache = OwnerCredentialCache(listOf(key(7)))
        val first = cache.credentials("synthetic-tag")
        assertArrayEquals(ByteArray(16) { 7 },first.masterSecret)
        first.close(); assertArrayEquals(ByteArray(16),first.masterSecret)
        cache.credentials("synthetic-tag").use { assertArrayEquals(ByteArray(16) { 7 },it.masterSecret) }
    }
    @Test fun replacingOrClearingKeysTakesEffectWithoutATokenLock() {
        val cache = OwnerCredentialCache(listOf(key(7)))
        cache.replace(listOf(key(8)))
        cache.credentials("synthetic-tag").use { assertArrayEquals(ByteArray(16) { 8 },it.masterSecret) }
        cache.replace(emptyList())
        assertThrows(IllegalStateException::class.java) { cache.credentials("synthetic-tag") }
    }
    @Test fun invalidKeyIsRejectedAndSecretDescriptionIsRedacted() {
        val cache = OwnerCredentialCache(listOf(key(7).copy(encodedKey="")))
        assertThrows(IllegalStateException::class.java) { cache.credentials("synthetic-tag") }
        assertEquals("OwnerKey(REDACTED)",key(7).toString())
    }
    @Test fun concurrentReadersAlwaysReceiveACompleteSnapshot() {
        val cache = OwnerCredentialCache(listOf(key(7)))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit { repeat(1_000) { cache.replace(listOf(key(if (it % 2 == 0) 7 else 8))) } }
            val reader = pool.submit { repeat(1_000) {
                cache.credentials("synthetic-tag").use { credentials ->
                    val value = credentials.masterSecret.first()
                    assertTrue(value == 7.toByte() || value == 8.toByte())
                    assertTrue(credentials.masterSecret.all { it == value })
                }
            } }
            writer.get(5,TimeUnit.SECONDS); reader.get(5,TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }
}
