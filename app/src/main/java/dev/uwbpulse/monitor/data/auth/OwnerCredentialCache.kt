package dev.uwbpulse.monitor.data.auth

import dev.uwbpulse.monitor.data.SmartTagCrypto
import java.util.Base64

/** Immutable key snapshots: BLE callbacks never wait for a token refresh or disk/network I/O. */
internal data class OwnerKey(val id: String, val encodedKey: String, val encodedIv: String) {
    override fun toString() = "OwnerKey(REDACTED)"
}

internal class OwnerCredentialCache(initial: List<OwnerKey>) {
    @Volatile private var records = initial.associateBy { it.id }
    fun replace(value: List<OwnerKey>) { records = value.associateBy { it.id } }
    fun credentials(id: String): TagCredentials {
        val tag = records[id] ?: error("TAG_NOT_IN_ACCOUNT")
        val full = decode(tag.encodedKey) ?: error("TAG_KEY_NOT_AVAILABLE")
        val master = try { SmartTagCrypto.ownerMasterSecret(full) } finally { full.fill(0) }
        return TagCredentials(master, decode(tag.encodedIv))
    }
    private fun decode(value: String): ByteArray? {
        if (value.isBlank()) return null
        val clean = value.filterNot(Char::isWhitespace)
        return runCatching { Base64.getUrlDecoder().decode(clean) }.getOrNull()
            ?: runCatching { Base64.getDecoder().decode(clean) }.getOrNull()
    }
}
