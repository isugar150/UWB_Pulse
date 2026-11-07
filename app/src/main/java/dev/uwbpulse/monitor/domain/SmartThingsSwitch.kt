package dev.uwbpulse.monitor.domain

import java.net.URLEncoder
import java.util.Base64

/**
 * Mirrors the exit state into a SmartThings virtual switch, so a SmartThings automation can use
 * "switch turned on" as its condition. Pure helpers only: no Android, no network, no secrets in logs.
 */
object SmartThingsSwitch {
    const val API_HOST = "api.smartthings.com"
    const val TOKEN_URL = "https://$API_HOST/oauth/token"
    const val AUTHORIZE_URL = "https://$API_HOST/oauth/authorize"
    /** A reserved, never-resolving host: the browser shows an error page but the address bar keeps the code, and no third party sees it. */
    const val DEFAULT_REDIRECT = "https://uwbpulse.invalid/callback"
    const val SCOPE = "r:devices:* w:devices:* x:devices:*"
    private val deviceIdPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    data class Tokens(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long)

    fun isDeviceId(value: String) = deviceIdPattern.matches(value)

    /** Exit turns the switch on, return turns it off. Other events do not touch it. */
    fun stateFor(type: MonitorEventType): String? = when (type) {
        MonitorEventType.TAG_DISTANCE_EXCEEDED -> "on"
        MonitorEventType.TAG_RETURNED -> "off"
        else -> null
    }

    fun eventsUrl(deviceId: String): String {
        require(isDeviceId(deviceId)) { "SMARTTHINGS_DEVICE_ID_INVALID" }
        return "https://$API_HOST/virtualdevices/$deviceId/events" // same unversioned path the SmartThings SDK uses
    }

    fun eventsBody(state: String): String {
        require(state == "on" || state == "off") { "SMARTTHINGS_STATE_INVALID" }
        return """{"deviceEvents":[{"component":"main","capability":"switch","attribute":"switch","value":"$state"}]}"""
    }

    fun basicAuth(clientId: String, clientSecret: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    fun authorizationUrl(clientId: String, redirectUri: String): String =
        "$AUTHORIZE_URL?client_id=${enc(clientId)}&response_type=code&redirect_uri=${enc(redirectUri)}&scope=${enc(SCOPE)}"

    fun codeExchangeForm(clientId: String, code: String, redirectUri: String): String =
        "grant_type=authorization_code&client_id=${enc(clientId)}&code=${enc(code)}&redirect_uri=${enc(redirectUri)}"

    fun refreshForm(clientId: String, refreshToken: String): String =
        "grant_type=refresh_token&client_id=${enc(clientId)}&refresh_token=${enc(refreshToken)}"

    /** Reads the flat fields of the token response; the refresh token is rotated by SmartThings, so it is optional here. */
    fun parseTokens(body: String): Tokens {
        fun string(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        val access = requireNotNull(string("access_token")) { "SMARTTHINGS_TOKEN_RESPONSE_INVALID" }
        val expires = Regex("\"expires_in\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLong() ?: 3_600L
        return Tokens(access, string("refresh_token"), expires)
    }

    /** A token that ends within this margin is renewed before it is used. */
    const val RENEW_MARGIN_MILLIS = 5 * 60_000L

    fun needsRenewal(accessToken: String, expiresAtEpochMillis: Long, nowEpochMillis: Long): Boolean =
        accessToken.isBlank() || expiresAtEpochMillis - nowEpochMillis < RENEW_MARGIN_MILLIS
}
