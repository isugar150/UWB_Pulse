package dev.uwbpulse.monitor.automation

import android.content.Context
import androidx.work.*
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.data.auth.SecretVault
import dev.uwbpulse.monitor.domain.SmartThingsSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

/** Everything here is a secret: it lives only in the Keystore-encrypted vault and is never logged. */
data class SmartThingsConfig(
    val clientId: String = "", val clientSecret: String = "", val refreshToken: String = "",
    val accessToken: String = "", val accessExpiresAt: Long = 0, val deviceId: String = "",
    val redirectUri: String = SmartThingsSwitch.DEFAULT_REDIRECT,
) {
    override fun toString() = "SmartThingsConfig(REDACTED)"
    val connected get() = clientId.isNotBlank() && clientSecret.isNotBlank() && refreshToken.isNotBlank()
}

class SmartThingsStore(context: Context) {
    private val vault = SecretVault(context, "smartthings")
    @Synchronized fun read(): SmartThingsConfig = runCatching {
        vault.read().let {
            SmartThingsConfig(it.optString("clientId"), it.optString("clientSecret"), it.optString("refreshToken"), it.optString("accessToken"),
                it.optLong("accessExpiresAt"), it.optString("deviceId"), it.optString("redirectUri").ifBlank { SmartThingsSwitch.DEFAULT_REDIRECT })
        }
    }.getOrDefault(SmartThingsConfig())
    @Synchronized fun write(value: SmartThingsConfig) = vault.write(JSONObject().put("clientId", value.clientId).put("clientSecret", value.clientSecret)
        .put("refreshToken", value.refreshToken).put("accessToken", value.accessToken).put("accessExpiresAt", value.accessExpiresAt)
        .put("deviceId", value.deviceId).put("redirectUri", value.redirectUri))
    @Synchronized fun update(block: (SmartThingsConfig) -> SmartThingsConfig) = write(block(read()))
    @Synchronized fun clear() = vault.clear()
}

/** HTTPS to api.smartthings.com only, no redirects, bounded time and size. */
internal object SmartThingsHttp {
    data class Response(val status: Int, val body: String)
    fun post(url: String, headers: Map<String, String>, contentType: String, body: String) = request("POST", url, headers, contentType, body)
    fun get(url: String, headers: Map<String, String>) = request("GET", url, headers, null, null)
    private fun request(method: String, url: String, headers: Map<String, String>, contentType: String?, body: String?): Response {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == SmartThingsSwitch.API_HOST && uri.userInfo == null && uri.port == -1) { "SMARTTHINGS_HOST_REJECTED" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input ->
                val buffer = ByteArray(8192); val output = java.io.ByteArrayOutputStream()
                while (true) { val count = input.read(buffer); if (count < 0) break; check(output.size() + count <= 65_536) { "SMARTTHINGS_RESPONSE_TOO_LARGE" }; output.write(buffer, 0, count) }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return Response(status, text)
        } finally { connection.disconnect() }
    }
}

/** Feasibility check: can the already stored Samsung login token read and change the virtual switch? Only HTTP statuses leave this object. */
object SmartThingsProbe {
    data class Result(val readStatus: Int, val writeStatus: Int?)
    fun run(account: dev.uwbpulse.monitor.data.auth.SamsungAccountRepository, deviceId: String): Result {
        val statusUrl = "https://${SmartThingsSwitch.API_HOST}/v1/devices/$deviceId/status"
        require(SmartThingsSwitch.isDeviceId(deviceId)) { "SMARTTHINGS_DEVICE_ID_INVALID" }
        fun bearer(token: String) = mapOf("Authorization" to "Bearer $token")
        var read = SmartThingsHttp.get(statusUrl, bearer(account.accessToken()))
        if (read.status == 401) read = SmartThingsHttp.get(statusUrl, bearer(account.accessToken(renew = true)))
        if (read.status !in 200..299) return Result(read.status, null)
        // Writing "off" is harmless: nothing is attached to the off state.
        val write = SmartThingsHttp.post(SmartThingsSwitch.eventsUrl(deviceId), bearer(account.accessToken()), "application/json", SmartThingsSwitch.eventsBody("off"))
        return Result(read.status, write.status)
    }
}

object SmartThingsClient {
    private fun tokenRequest(config: SmartThingsConfig, form: String): SmartThingsSwitch.Tokens {
        val response = SmartThingsHttp.post(SmartThingsSwitch.TOKEN_URL, mapOf("Authorization" to SmartThingsSwitch.basicAuth(config.clientId, config.clientSecret)),
            "application/x-www-form-urlencoded", form)
        if (response.status == 400 || response.status == 401) error("SMARTTHINGS_REAUTH_REQUIRED")
        check(response.status in 200..299) { "SMARTTHINGS_TOKEN_HTTP_${response.status}" }
        return SmartThingsSwitch.parseTokens(response.body)
    }
    private fun store(store: SmartThingsStore, tokens: SmartThingsSwitch.Tokens, now: Long) = store.update {
        it.copy(accessToken = tokens.accessToken, accessExpiresAt = now + tokens.expiresInSeconds * 1000, refreshToken = tokens.refreshToken ?: it.refreshToken)
    }
    /** One-time exchange of the pasted authorization code. The refresh token is required to stay connected. */
    fun exchangeCode(store: SmartThingsStore, code: String) {
        val config = store.read()
        check(config.clientId.isNotBlank() && config.clientSecret.isNotBlank()) { "SMARTTHINGS_NOT_CONFIGURED" }
        val tokens = tokenRequest(config, SmartThingsSwitch.codeExchangeForm(config.clientId, code.trim(), config.redirectUri))
        checkNotNull(tokens.refreshToken) { "SMARTTHINGS_NO_REFRESH_TOKEN" }
        store(store, tokens, System.currentTimeMillis())
    }
    /** Serialised: SmartThings rotates the refresh token, so two refreshes must never use the same one. */
    fun accessToken(store: SmartThingsStore, force: Boolean = false): String = synchronized(store) {
        val config = store.read()
        check(config.connected) { "SMARTTHINGS_NOT_CONNECTED" }
        val now = System.currentTimeMillis()
        if (!force && !SmartThingsSwitch.needsRenewal(config.accessToken, config.accessExpiresAt, now)) return config.accessToken
        val tokens = tokenRequest(config, SmartThingsSwitch.refreshForm(config.clientId, config.refreshToken))
        store(store, tokens, now)
        tokens.accessToken
    }
    /**
     * Returns the HTTP status of the events call; a 401 renews the token once and repeats.
     * The stored Samsung login is used unless an OAuth app was connected, in which case that token is used.
     */
    fun sendState(store: SmartThingsStore, account: dev.uwbpulse.monitor.data.auth.SamsungAccountRepository, state: String): Int {
        val config = store.read()
        val url = SmartThingsSwitch.eventsUrl(config.deviceId)
        val body = SmartThingsSwitch.eventsBody(state)
        fun token(renew: Boolean) = if (config.connected) accessToken(store, renew) else account.accessToken(renew)
        fun call(token: String) = SmartThingsHttp.post(url, mapOf("Authorization" to "Bearer $token"), "application/json", body)
        var response = call(token(false))
        if (response.status == 401) response = call(token(true))
        return response.status
    }
}

class SmartThingsSwitchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MonitorApplication
        val state = inputData.getString(STATE) ?: return@withContext Result.failure()
        // A test press works even when the automation is switched off; real events respect the switch.
        if (!inputData.getBoolean(TEST, false) && !app.settings.persisted().smartThingsEnabled) return@withContext Result.success()
        if (runAttemptCount >= 5) { app.store.record("ST_SWITCH_FAILED", "retry limit reached; state=$state"); return@withContext Result.failure() }
        try {
            val status = SmartThingsClient.sendState(app.smartThings, app.account, state)
            app.store.record("ST_SWITCH_RESULT", "state=$state; httpStatus=$status; attempt=${runAttemptCount + 1}")
            when { status in 200..299 -> Result.success(); status == 429 || status in 500..599 -> Result.retry(); else -> Result.failure() }
        } catch (e: Exception) {
            val code = e.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: e.javaClass.simpleName
            app.store.record("ST_SWITCH_ERROR", "state=$state; $code")
            if (e is IllegalArgumentException || code == "SMARTTHINGS_NOT_CONNECTED" || code == "SMARTTHINGS_REAUTH_REQUIRED") Result.failure() else Result.retry()
        }
    }
    companion object { const val STATE = "state"; const val TEST = "test" }
}

/** The refresh token expires if unused for about a month, so it is renewed daily while the feature is on. */
class SmartThingsKeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MonitorApplication
        // Only an OAuth app needs this: the Samsung login is renewed whenever it is used.
        if (!app.settings.persisted().smartThingsEnabled || !app.smartThings.read().connected) return@withContext Result.success()
        try { SmartThingsClient.accessToken(app.smartThings, force = true); app.store.record("ST_KEEPALIVE", "token renewed"); Result.success() }
        catch (e: Exception) {
            val code = e.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: e.javaClass.simpleName
            app.store.record("ST_KEEPALIVE_ERROR", code)
            if (code == "SMARTTHINGS_REAUTH_REQUIRED" || code == "SMARTTHINGS_NOT_CONNECTED") Result.failure() else Result.retry()
        }
    }
}

object SmartThingsWork {
    private const val SWITCH = "uwbpulse-st-switch"
    private const val KEEPALIVE = "uwbpulse-st-keepalive"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Only the newest desired state matters, so a pending older request is replaced. */
    fun sendState(context: Context, state: String, test: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SmartThingsSwitchWorker>()
            .setInputData(workDataOf(SmartThingsSwitchWorker.STATE to state, SmartThingsSwitchWorker.TEST to test))
            .setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SWITCH, ExistingWorkPolicy.REPLACE, request)
    }

    fun syncKeepAlive(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) { manager.cancelUniqueWork(KEEPALIVE); return }
        val request = PeriodicWorkRequestBuilder<SmartThingsKeepAliveWorker>(24, TimeUnit.HOURS).setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS).build()
        manager.enqueueUniquePeriodicWork(KEEPALIVE, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
