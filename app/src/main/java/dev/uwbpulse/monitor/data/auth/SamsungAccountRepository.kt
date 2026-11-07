package dev.uwbpulse.monitor.data.auth

import android.content.Context
import android.net.Uri
import dev.uwbpulse.monitor.data.SmartTagCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class OwnedTag(val id: String, val name: String, val supportsUwb: Boolean, val hasOwnerKey: Boolean)
class TagCredentials(val masterSecret: ByteArray, val privacyIv: ByteArray?) : AutoCloseable {
    override fun close() { masterSecret.fill(0); privacyIv?.fill(0) }
    override fun toString() = "TagCredentials(REDACTED)"
}
class SamsungAccountRepository(context: Context) {
    private val vault = SecretVault(context)
    // Only IO callers acquire this lock. Key lookups use an independent immutable snapshot.
    private val sessionLock = Any()
    private var pending: SamsungLogin? = null
    @Volatile private var saved = runCatching { vault.read() }.getOrElse { JSONObject() }
    @Volatile private var hasLogin = saved.optString("accessToken").isNotBlank()
    private val credentialCache = OwnerCredentialCache(readKeys())
    private val mutableTags = MutableStateFlow(readTags())
    val tags = mutableTags.asStateFlow()
    val selectedId = MutableStateFlow(saved.optString("selectedTag").ifBlank { null })
    val status = MutableStateFlow(if (saved.has("accessToken")) "삼성 계정 연결됨" else "삼성 계정 로그인 필요")
    val busy = MutableStateFlow(false)
    private fun readTags(): List<OwnedTag> = (saved.optJSONArray("tags") ?: JSONArray()).let { list ->
        (0 until list.length()).map { i -> val tag = list.getJSONObject(i)
            OwnedTag(tag.getString("id"),tag.optString("name","SmartTag"),tag.optBoolean("uwb"),tag.optString("key").isNotBlank()) }
    }
    private fun readKeys(): List<OwnerKey> = (saved.optJSONArray("tags") ?: JSONArray()).let { list ->
        (0 until list.length()).map { i -> val tag = list.getJSONObject(i)
            OwnerKey(tag.getString("id"),tag.optString("key"),tag.optString("privacyIv")) }
    }
    suspend fun loginUrl(): String = withContext(Dispatchers.IO) { synchronized(sessionLock) {
        val id = saved.optString("deviceId").ifBlank { UUID.randomUUID().toString().replace("-","") }
        saved.put("deviceId",id); vault.write(saved)
        SamsungLogin(id).also { pending = it }.createUrl()
    } }
    suspend fun completeLogin(uri: Uri) = withContext(Dispatchers.IO) { synchronized(sessionLock) {
        val flow = checkNotNull(pending) { "LOGIN_SESSION_MISSING" } // Ignore unsolicited/replayed callbacks without poisoning an existing session.
        busy.value = true
        try {
            pending = null
            val tokens = flow.finish(uri)
            tokens.put("tags",saved.optJSONArray("tags") ?: JSONArray())
            tokens.put("selectedTag",selectedId.value)
            saved = tokens; vault.write(saved); hasLogin = true
            fetchTags()
            status.value = "삼성 계정 연결됨 · 태그 ${mutableTags.value.size}개"
        } catch (e: Exception) { status.value = safeError(e); throw e }
        finally { busy.value = false }
    } }
    suspend fun refresh() = withContext(Dispatchers.IO) { synchronized(sessionLock) {
        busy.value = true
        try { fetchTags(); status.value = "태그 ${mutableTags.value.size}개 조회됨" }
        catch (e: Exception) { status.value = safeError(e); throw e }
        finally { busy.value = false }
    } }
    private fun fetchTags() {
        var token = saved.optString("accessToken").also { check(it.isNotBlank()) { "SAMSUNG_LOGIN_REQUIRED" } }
        fun getDevices() = SamsungHttp.request("https://client.smartthings.com/devices/",token=token)
        val result = try { getDevices() } catch (e: IllegalStateException) {
            if (e.message != "SAMSUNG_HTTP_401") throw e
            token = refreshAccessToken()
            getDevices()
        }
        val items = result.optJSONArray("items") ?: error("DEVICE_LIST_FORMAT_UNSUPPORTED")
        val tags = JSONArray()
        for (i in 0 until items.length()) {
            var device = items.getJSONObject(i)
            val d2d = device.optJSONObject("bleD2D") ?: continue
            val deviceText = device.optString("ocfDeviceType")
            val components = device.optJSONArray("components")?.toString().orEmpty()
            if (!deviceText.contains("tag",true) && !components.contains("tag.")) continue
            val id = device.getString("deviceId")
            if (d2d.optString("encryptionKey").isBlank()) {
                device = SamsungHttp.request("https://client.smartthings.com/device/${SamsungHttp.enc(id)}",token=token)
            }
            val ble = device.optJSONObject("bleD2D") ?: continue
            val key = ble.optString("encryptionKey")
            val metadata = ble.optJSONObject("metadata")
            val tag = JSONObject().put("id",id).put("name",device.optString("label").ifBlank { device.optString("name","SmartTag") })
                .put("uwb",components.contains("tag.uwbActivation")).put("key",key)
                .put("privacyIv",metadata?.optString("privacyIdInitialVector").orEmpty())
            tags.put(tag)
        }
        saved.put("tags",tags); vault.write(saved); credentialCache.replace(readKeys()); mutableTags.value = readTags()
        if (selectedId.value == null && mutableTags.value.size == 1) {
            selectedId.value = mutableTags.value.single().id
            saved.put("selectedTag",selectedId.value); vault.write(saved)
        }
    }
    suspend fun selectTag(id: String) = withContext(Dispatchers.IO) { synchronized(sessionLock) {
        check(mutableTags.value.any { it.id == id }) { "TAG_NOT_IN_ACCOUNT" }
        selectedId.value = id; saved.put("selectedTag",id); vault.write(saved)
    } }
    /** Called only while sessionLock is held; rotating refresh tokens are never used concurrently. */
    private fun refreshAccessToken(): String {
        val base = SamsungHttp.verifyAuthUrl(saved.getString("authBase"))
        val response = SamsungHttp.request("$base/auth/oauth2/token","POST",mapOf("grant_type" to "refresh_token", "client_id" to SamsungLogin.IOT_CLIENT,"refresh_token" to saved.getString("refreshToken")))
        val token = response.getString("access_token")
        saved.put("accessToken",token).put("refreshToken",response.optString("refresh_token",saved.getString("refreshToken")))
        vault.write(saved)
        return token
    }
    fun loggedIn(): Boolean = hasLogin
    /** The logged-in Samsung account's access token, optionally renewed first. Never log or display it. */
    fun accessToken(renew: Boolean = false): String = synchronized(sessionLock) {
        val current = saved.optString("accessToken")
        check(current.isNotBlank()) { "SAMSUNG_LOGIN_REQUIRED" }
        if (renew) refreshAccessToken() else current
    }
    fun credentials(id: String): TagCredentials = credentialCache.credentials(id)
    fun matchAdvertisement(bytes: ByteArray): String? {
        for (tag in mutableTags.value) {
            val matches = runCatching { credentials(tag.id).use { secret -> secret.privacyIv?.let { SmartTagCrypto.matchesAdvertisement(secret.masterSecret,it,bytes) } ?: false } }.getOrDefault(false)
            if (matches) return tag.id
        }
        return null
    }
    suspend fun logout() = withContext(Dispatchers.IO) { synchronized(sessionLock) {
        saved = JSONObject(); pending = null; vault.clear(); hasLogin = false; credentialCache.replace(emptyList())
        mutableTags.value = emptyList(); selectedId.value = null; status.value = "삼성 계정 로그인 필요"
    } }
    companion object {
        fun safeError(e: Exception): String = e.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: e.javaClass.simpleName
    }
}
