package dev.uwbpulse.monitor.data.auth

import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** HTTPS only, no automatic redirects or HTTP logging (URLs can contain OAuth material). */
object SamsungHttp {
    fun samsungHost(host: String): Boolean = listOf("samsung.com", "samsungosp.com").any { host == it || host.endsWith(".$it") }
    fun verifyAuthUrl(url: String): String {
        val uri = URI(url)
        check(uri.scheme == "https" && uri.userInfo == null && samsungHost(uri.host.orEmpty().lowercase()) && uri.port in listOf(-1, 443)) { "AUTH_HOST_REJECTED" }
        return url
    }
    fun form(values: Map<String, String>) = values.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
    fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    fun request(url: String, method: String = "GET", fields: Map<String, String>? = null, token: String? = null): JSONObject {
        val uri = URI(url)
        check(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1,443)) { "HTTPS_REQUIRED" }
        check(if (token == null) samsungHost(uri.host.orEmpty()) else uri.host == "client.smartthings.com") { "API_HOST_REJECTED" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", if (token == null) "application/json" else "application/vnd.smartthings+json;v=1")
            connection.setRequestProperty("User-Agent", "Android/Oneapp/1.8.52.23 (${Build.MODEL}; Android ${Build.VERSION.SDK_INT}/${Build.VERSION.RELEASE}) 4.11.0 QcApplication")
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-St-Client-Appversion", "1.8.52.23")
                connection.setRequestProperty("X-St-Client-Os", "Android ${Build.VERSION.RELEASE}")
                connection.setRequestProperty("X-St-Client-Devicemodel", "${Build.MANUFACTURER} ${Build.MODEL} ${Build.DEVICE}")
            } else {
                connection.setRequestProperty("X-Osp-Clientosversion", Build.VERSION.SDK_INT.toString())
                connection.setRequestProperty("X-Osp-Clientmodel", Build.MODEL)
                connection.setRequestProperty("X-Osp-Appid", SamsungLogin.LOGIN_CLIENT)
                connection.setRequestProperty("X-Osp-Packagename", "com.samsung.android.oneconnect")
                connection.setRequestProperty("X-Osp-Packageversion", "1.8.52.23")
            }
            if (fields != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form(fields).toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            check(status in 200..299) { "SAMSUNG_HTTP_$status" }
            val text = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192); val output = StringBuilder()
                while (true) { val count = reader.read(buffer); if (count < 0) break; check(output.length + count <= 4_000_000) { "API_RESPONSE_TOO_LARGE" }; output.append(buffer,0,count) }
                output.toString()
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
}
