package dev.uwbpulse.monitor.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.work.*
import dev.uwbpulse.monitor.MainActivity
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.data.auth.SecretVault
import dev.uwbpulse.monitor.domain.MonitorEventType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MonitorEvent(val id: String, val type: MonitorEventType, val tagAlias: String, val meters: Double?, val threshold: Double, val timestamp: String) {
    fun json(includeTag: Boolean = true) = JSONObject().put("id",id).put("event",type.name)
        .put("distanceMeters",meters ?: JSONObject.NULL).put("thresholdMeters",threshold).put("timestamp",timestamp).apply { if (includeTag) put("tagId",tagAlias) }
    companion object {
        fun tagAlias(packageName: String,cloudId: String) = MessageDigest.getInstance("SHA-256").digest((packageName + "|" + cloudId).toByteArray()).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
data class WebhookConfiguration(val url: String = "", val token: String = "") {
    override fun toString() = "WebhookConfiguration(REDACTED)"
    fun validate() {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "WEBHOOK_HTTPS_URL_REQUIRED" }
        require(token.none { it == '\r' || it == '\n' }) { "WEBHOOK_TOKEN_INVALID" }
    }
}
class EventDispatcher(private val app: MonitorApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private sealed interface Dispatch {
        data class Event(val event: MonitorEvent) : Dispatch
        data class SwitchState(val state: String, val atEpochMillis: Long) : Dispatch
    }
    private val pending = Channel<Dispatch>(Channel.UNLIMITED)
    private val file = File(app.filesDir,"monitor-events.json")
    private val webhookVault = SecretVault(app,"webhook")
    private val mutable = MutableStateFlow(loadEvents())
    val events = mutable.asStateFlow()
    init {
        scope.launch {
            for (item in pending) {
                try {
                    when (item) {
                        is Dispatch.Event -> dispatch(item.event)
                        is Dispatch.SwitchState -> if (app.settings.persisted().smartThingsEnabled) {
                            SmartThingsWork.sendState(app,item.state,createdAtEpochMillis=item.atEpochMillis)
                        }
                    }
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { app.store.record("EVENT_DISPATCH_ERROR",e.javaClass.simpleName) }
            }
        }
    }
    private fun loadEvents(): List<MonitorEvent> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i -> val item = array.getJSONObject(i)
            MonitorEvent(item.getString("id"),MonitorEventType.valueOf(item.getString("event")),item.optString("tagId"),
                if (item.isNull("distanceMeters")) null else item.getDouble("distanceMeters"),item.getDouble("thresholdMeters"),item.getString("timestamp")) }
    }.getOrDefault(emptyList())
    fun publish(type: MonitorEventType, cloudId: String, meters: Double?, threshold: Double) {
        val alias = MonitorEvent.tagAlias(app.packageName,cloudId)
        val event = MonitorEvent(UUID.randomUUID().toString(),type,alias,meters,threshold,OffsetDateTime.now().toString())
        check(pending.trySend(Dispatch.Event(event)).isSuccess) { "EVENT_QUEUE_CLOSED" }
    }
    fun syncSmartThings(state: String) {
        check(pending.trySend(Dispatch.SwitchState(state,System.currentTimeMillis())).isSuccess) { "EVENT_QUEUE_CLOSED" }
    }
    private suspend fun dispatch(event: MonitorEvent) {
            val type = event.type
            mutable.value = (mutable.value + event).takeLast(100)
            val atomic = android.util.AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(JSONArray(mutable.value.map { it.json() }).toString().toByteArray()); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); app.store.record("EVENT_PERSIST_ERROR",e.javaClass.simpleName) }
            app.store.record(type.name,"distanceMeters=${event.meters ?: "unknown"}; thresholdMeters=${event.threshold}; eventId=${event.id}")
            val preferences = app.settings.state.value
            if (type in preferences.notifications) notifyEvent(event)
            if (preferences.taskerEnabled) runCatching { TaskerEvents.send(app,event) }.onFailure { app.store.record("TASKER_DISPATCH_ERROR",it.javaClass.simpleName) }
            if (preferences.webhookEnabled) {
                val request = OneTimeWorkRequestBuilder<WebhookWorker>()
                    .setInputData(workDataOf("event" to event.json(preferences.includeTagId).toString()))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
                WorkManager.getInstance(app).enqueueUniqueWork("uwbpulse-event-${event.id}",ExistingWorkPolicy.KEEP,request)
            }
            if (preferences.smartThingsEnabled) dev.uwbpulse.monitor.domain.SmartThingsSwitch.stateFor(type)?.let {
                SmartThingsWork.sendState(app,it,createdAtEpochMillis=OffsetDateTime.parse(event.timestamp).toInstant().toEpochMilli())
            }
    }
    private fun notifyEvent(event: MonitorEvent) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(NotificationManager::class.java)
        val channel = "event_${event.type.name}"
        manager.createNotificationChannel(NotificationChannel(channel,event.type.name,NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app,0,Intent(app,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val text = event.meters?.let { "실제 UWB %.2f m · 설정 %.1f m".format(it,event.threshold) } ?: "거리 수신·연결 상태를 확인하세요."
        manager.notify(event.type.ordinal + 10,NotificationCompat.Builder(app,channel).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(event.type.name).setContentText(text).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
    fun webhook(): WebhookConfiguration = runCatching { webhookVault.read().let { WebhookConfiguration(it.optString("url"),it.optString("token")) } }.getOrDefault(WebhookConfiguration())
    suspend fun saveWebhook(url: String, newToken: String, clearToken: Boolean) = withContext(Dispatchers.IO) {
        val config = WebhookConfiguration(url.trim(),if (clearToken) "" else newToken.ifBlank { webhook().token })
        config.validate(); webhookVault.write(JSONObject().put("url",config.url).put("token",config.token))
    }
    fun isRecorded(id: String, type: String): Boolean = mutable.value.any { it.id == id && it.type.name == type }
}

class WebhookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MonitorApplication
        // A disabled endpoint never receives queued events.
        if (!app.settings.persisted().webhookEnabled) return@withContext Result.success()
        val event = inputData.getString("event") ?: return@withContext Result.failure()
        if (runAttemptCount >= 3) { app.store.record("WEBHOOK_FAILED","retry limit reached"); return@withContext Result.failure() }
        var connection: HttpURLConnection? = null
        try {
            val config = app.events.webhook().also { it.validate() }
            connection = URI(config.url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 5_000; connection.readTimeout = 5_000
            connection.setRequestProperty("Content-Type","application/json")
            connection.setRequestProperty("Idempotency-Key",JSONObject(event).getString("id"))
            if (config.token.isNotBlank()) connection.setRequestProperty("Authorization","Bearer ${config.token}")
            connection.outputStream.use { it.write(event.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            app.store.record("WEBHOOK_RESULT","httpStatus=$code; attempt=${runAttemptCount + 1}")
            when { code in 200..299 -> Result.success(); (code == 429 || code in 500..599) && runAttemptCount < 2 -> Result.retry(); else -> Result.failure() }
        } catch (e: Exception) {
            app.store.record("WEBHOOK_ERROR",e.javaClass.simpleName)
            if (e is IllegalArgumentException || runAttemptCount >= 2) Result.failure() else Result.retry()
        } finally { connection?.disconnect() }
    }
}
