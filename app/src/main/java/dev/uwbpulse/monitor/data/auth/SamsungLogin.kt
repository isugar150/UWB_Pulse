/* SPDX-License-Identifier: GPL-3.0-only
 * Samsung web login wire format adapted from uTag SignInUtils/AuthService (Kieron Quinn).
 * Uses the published uTag account-client flow, not a Samsung partner SDK.
 */
package dev.uwbpulse.monitor.data.auth

import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SamsungLogin(private val deviceId: String) {
    private val random = SecureRandom()
    private val state = randomText(20)
    private val verifier = randomText(43)
    private var consumed = false
    private val createdAt = android.os.SystemClock.elapsedRealtime()
    private fun randomText(size: Int): String = buildString { repeat(size) { append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(62)]) } }
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun challenge(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    fun createUrl(): String {
        val entry = SamsungHttp.request("https://account.samsung.com/accounts/ANDROIDSDK/getEntryPoint")
        val base = SamsungHttp.verifyAuthUrl(entry.getString("signInURI"))
        val iterations = entry.get("chkDoNum").toString().toInt().also { check(it in 1..100_000) { "LOGIN_KDF_PARAMETER_INVALID" } }
        val params = JSONObject().apply {
            put("clientId", LOGIN_CLIENT); put("code_challenge", challenge()); put("code_challenge_method", "S256")
            put("competitorDeviceYNFlag", if (Build.MANUFACTURER.equals("samsung",true)) "N" else "Y")
            put("countryCode", Locale.getDefault().country.lowercase().ifBlank { "kr" })
            put("deviceInfo", "${Build.MANUFACTURER}|com.android.chrome")
            put("deviceModelID", Build.MODEL); put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("deviceOSVersion", Build.VERSION.SDK_INT.toString()); put("devicePhysicalAddressText", "ANID:$deviceId")
            put("deviceType", "APP"); put("deviceUniqueID", deviceId); put("redirect_uri", REDIRECT)
            put("replaceableClientConnectYN", "N"); put("replaceableClientId", ""); put("replaceableDevicePhysicalAddressText", "")
            put("responseEncryptionType", "1"); put("responseEncryptionYNFlag", "Y"); put("scope", ""); put("state", state)
            put("svcIptLgnID", ""); put("iosYNFlag", "Y")
        }
        val password = b64(MessageDigest.getInstance("SHA-256").digest(iterations.toString().toByteArray())).toCharArray()
        val spec = PBEKeySpec(password, ByteArray(16).also(random::nextBytes), iterations, 128)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { password.fill('\u0000'); spec.clearPassword() }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(entry.getString("pkiPublicKey"))))
        val encryptedKey = Cipher.getInstance("RSA/ECB/PKCS1Padding").run { init(Cipher.ENCRYPT_MODE,publicKey); b64(doFinal(b64(key).toByteArray())) }
        val iv = ByteArray(16).also(random::nextBytes)
        val payload = Cipher.getInstance("AES/CBC/PKCS5Padding").run { init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(iv)); b64(doFinal(params.toString().toByteArray())) }
        key.fill(0)
        val envelope = JSONObject().put("chkDoNum",iterations.toString()).put("svcEncParam",payload).put("svcEncKY",encryptedKey).put("svcEncIV",iv.joinToString("") { "%02x".format(it.toInt() and 255) })
        return "$base?locale=${SamsungHttp.enc(Locale.getDefault().toLanguageTag())}&svcParam=${SamsungHttp.enc(b64(envelope.toString().toByteArray()))}&mode=C"
    }
    fun finish(uri: Uri): JSONObject {
        check(!consumed && android.os.SystemClock.elapsedRealtime() - createdAt < 15 * 60_000) { "LOGIN_EXPIRED" }
        check(uri.scheme == "ms-app" && uri.host == REDIRECT_HOST) { "LOGIN_REDIRECT_REJECTED" }
        val responseKey = decrypt(checkNotNull(uri.getQueryParameter("state")),state)
        check(responseKey.toByteArray().size >= 16) { "LOGIN_STATE_INVALID" }
        consumed = true
        val authHost = decrypt(checkNotNull(uri.getQueryParameter("auth_server_url")),responseKey)
        val base = SamsungHttp.verifyAuthUrl(if (authHost.startsWith("https://")) authHost else "https://$authHost").trimEnd('/')
        val code = decrypt(checkNotNull(uri.getQueryParameter("code")),responseKey)
        val username = decrypt(checkNotNull(uri.getQueryParameter("retValue")),responseKey)
        val account = SamsungHttp.request("$base/auth/oauth2/authenticate","POST",mapOf(
            "grant_type" to "authorization_code", "serviceType" to "M", "code" to code, "client_id" to LOGIN_CLIENT,
            "code_verifier" to verifier, "username" to username, "physical_address_text" to deviceId))
        val userAuth = account.getString("userauth_token")
        fun authorise(includeLogin: Boolean): JSONObject {
            val fields = linkedMapOf("response_type" to "code", "serviceType" to "M", "client_id" to IOT_CLIENT,
                "code_challenge_method" to "S256", "childAccountSupported" to "Y", "userauth_token" to userAuth,
                "code_challenge" to challenge(), "physical_address_text" to deviceId, "scope" to "iot.client")
            if (includeLogin) fields["login_id"] = username
            return SamsungHttp.request("$base/auth/oauth2/v2/authorize?${SamsungHttp.form(fields)}")
        }
        var authorization = authorise(true)
        if (authorization.optString("code").isBlank() && authorization.optString("privacyAccepted") == "N") authorization = authorise(false)
        val tokens = SamsungHttp.request("$base/auth/oauth2/token","POST",mapOf("grant_type" to "authorization_code",
            "code" to authorization.getString("code"), "client_id" to IOT_CLIENT, "code_verifier" to verifier, "physical_address_text" to deviceId))
        return JSONObject().put("accessToken", tokens.getString("access_token")).put("refreshToken",tokens.getString("refresh_token"))
            .put("authBase",base).put("userId",account.getString("userId")).put("deviceId",deviceId)
    }
    @android.annotation.SuppressLint("GetInstance") // Samsung's existing encrypted callback format; storage uses AES-GCM.
    private fun decrypt(value: String, key: String): String {
        check(value.length % 2 == 0 && value.length <= 65_536) { "LOGIN_RESPONSE_INVALID" }
        val bytes = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE,SecretKeySpec(key.toByteArray().copyOfRange(0,16),"AES"))
            String(doFinal(bytes),Charsets.UTF_8)
        }
    }
    companion object {
        const val LOGIN_CLIENT = "yfrtglt53o"
        const val IOT_CLIENT = "6iado3s6jc"
        const val REDIRECT_HOST = "s-1-15-2-4027708247-2189610-1983755848-2937435718-1578786913-2158692839-1974417358"
        const val REDIRECT = "ms-app://$REDIRECT_HOST"
    }
}
