package dev.uwbpulse.monitor.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import dev.uwbpulse.monitor.MainActivity
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.data.BleDiscovery
import dev.uwbpulse.monitor.data.GattTransport
import dev.uwbpulse.monitor.data.UwbProbe
import dev.uwbpulse.monitor.automation.MonitorEvent
import dev.uwbpulse.monitor.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ProbeService : Service() {
    private val app get() = application as MonitorApplication
    private val store get() = app.store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val power by lazy { getSystemService(PowerManager::class.java) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            scope.launch { app.settings.monitoring(false);stopSelf() }
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_STICKY
        val manualStart = intent != null && intent.action != RESUME
        val requestedBackend = runCatching { Backend.valueOf(intent?.getStringExtra(BACKEND) ?: "") }.getOrNull()
        try {
            ensureNotificationChannel(this)
            startForeground(1,notification("내 태그 연결 중"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            store.record("FGS_START_FAILED",e.javaClass.simpleName)
            store.update { ProbeState(stage="시작 실패",detail="서비스 권한과 근처 기기 설정을 확인하세요.") }
            stopSelf(); return START_NOT_STICKY
        }
        job = scope.launch {
            try {
                val saved = app.settings.persisted()
                if (!manualStart && !saved.monitoringEnabled) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return@launch }
                val cloudId = app.account.selectedId.value ?: error("SAMSUNG_LOGIN_REQUIRED")
                val backend = requestedBackend ?: saved.backend
                if (manualStart) app.settings.monitoring(true)
                app.settings.backend(backend)
                store.record("MONITOR_RESTORE","manual=$manualStart; backend=$backend; threshold=${saved.policy.threshold}; dwell=${saved.policy.dwellMillis}")
                monitor(cloudId,backend)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                store.record("MONITOR_START_ERROR",safe(e))
                store.update { it.copy(running=false,stage="시작 실패",detail=safe(e),measurement=null) }
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }
        }
        return START_STICKY // Re-read desired state on system recreation; never resume a user-stopped session.
    }
    private suspend fun addressFor(cloudId: String): String {
        store.update { it.copy(stage="내 태그 검색",detail="소유자 광고 서명을 확인하고 있습니다.",measurement=null) }
        return try {
            withTimeout(15_000) {
                BleDiscovery(this@ProbeService,store::record,app.account::matchAdvertisement).scan()
                    .map { candidates -> candidates.firstOrNull { it.cloudTagId == cloudId && it.connectable }?.address }
                    .filterNotNull().first()
            }
        } catch (_: TimeoutCancellationException) { error("OWNER_TAG_NOT_FOUND") }
    }
    private suspend fun monitor(cloudId: String,backend: Backend) {
        val initial = app.settings.persisted()
        val alias = MonitorEvent.tagAlias(packageName,cloudId)
        // The ranging interval is part of the encrypted tag configuration, so it is fixed for this service run.
        val rate = initial.trackingRate
        val engine = DistanceEventEngine(initial.policy,rate.maxGapMillis,initialAway=alias in initial.awayTagAliases)
        val evidence = ReceptionEvidence(rate.staleAfterMillis)
        val settingsJob = scope.launch { app.settings.state.collect { engine.configure(it.policy) } }
        var latest = LastObservation()
        var retries = 0
        var hasAuthenticated = false
        val outage = OutageGate()
        store.update { ProbeState(running=true,stage="연결 준비",detail="소유자 상호 인증 및 암호화 UWB 설정") }
        store.record("MONITOR_START","backend=$backend; rate=$rate; rescanMaxMs=${initial.rescanMaxMillis}; interactive=${power.isInteractive}; batteryExempt=${power.isIgnoringBatteryOptimizations(packageName)}")
        try {
            check(packageManager.hasSystemFeature("android.hardware.uwb")) { "NO_UWB_HARDWARE" }
            while (currentCoroutineContext().isActive) {
                val transport = GattTransport(this,store::record)
                var failure: String? = null
                try {
                    val address = addressFor(cloudId)
                    coroutineScope {
                        launch { val status = transport.disconnected.await(); error("GATT_DISCONNECTED_$status") }
                        transport.connect(address)
                        store.update { it.copy(stage="소유자 인증",detail="태그의 암호학적 응답 확인 중") }
                        app.account.credentials(cloudId).use { transport.authenticate(it.masterSecret) }
                        hasAuthenticated = true
                        store.update { it.copy(stage="UWB 연결 중",detail="암호화 세션 설정 · 실제 거리 수신 대기") }
                        val reportsBefore = evidence.count
                        launch {
                            delay(20_000)
                            if (evidence.count == reportsBefore) error("NO_UWB_REPORTS_20S")
                        }
                        launch {
                            while (isActive) {
                                delay(1_000)
                                val now = SystemClock.elapsedRealtime()
                                val fresh = evidence.isFresh(now)
                                store.update { it.copy(lastReportAgeMillis=evidence.age(now),measurement=if (fresh) it.measurement else null) }
                                if (!fresh && evidence.age(now) != null) {
                                    engine.signalLost()
                                    store.update { it.copy(stage="거리 수신 대기",detail="신호 중단 · 거리 이탈로 판정하지 않습니다.") }
                                    if ((evidence.age(now) ?: 0) > 20_000) error("UWB_REPORTS_STALE_20S")
                                }
                            }
                        }
                        var lastLog = 0L
                        var lastNotification = 0L
                        var lastUi = 0L
                        var lastSaved = 0L
                        UwbProbe(this@ProbeService,store::record).range(backend,rate) { transport.activate(it) }.collect { measurement ->
                            val now = measurement.elapsedMillis
                            if (!evidence.accept(measurement.meters,now,store.activityVisible,power.isInteractive)) return@collect
                            retries = 0; outage.recovered()
                            val event = engine.sample(measurement.meters,now)
                            event?.let {
                                app.settings.away(alias,engine.away)
                                app.events.publish(it,cloudId,measurement.meters,engine.policy.threshold)
                            }
                            val seenAt = System.currentTimeMillis()
                            latest = LastObservation(measurement.meters,seenAt)
                            val stage = if (engine.away) "이탈 감지됨" else "실제 UWB 수신"
                            // With the screen off or the app hidden nobody watches the number: refresh the UI state at most once a second.
                            if (store.activityVisible || event != null || stage != store.state.value.stage || now - lastUi >= HIDDEN_UI_MILLIS) {
                                lastUi = now
                                store.update { it.copy(stage=stage,detail=if (engine.away) "복귀 거리 안으로 진입하면 다시 감지합니다." else "실시간 거리 모니터링 중",
                                    measurement=measurement,lastReportAgeMillis=0) }
                            }
                            if (event != null || now - lastSaved >= LAST_STATE_MILLIS) {
                                lastSaved = now
                                scope.launch { app.settings.lastObservation(measurement.meters,seenAt) }
                            }
                            if (now - lastLog >= 5_000) {
                                lastLog = now
                                store.record("UWB_REPORT","meters=${measurement.meters}; confidence=${measurement.confidence}; activityVisible=${store.activityVisible}; interactive=${power.isInteractive}; total=${evidence.count}; background=${evidence.backgroundCount}; screenOff=${evidence.screenOffCount}")
                            }
                            if (now - lastNotification >= 10_000) {
                                lastNotification = now
                                updateNotification(if (engine.away) "이탈 감지 · 복귀 대기" else "실제 UWB 수신 중 · 임계값 ${engine.policy.threshold} m")
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failure = safe(e)
                    engine.signalLost()
                    store.record("MONITOR_CONNECTION_ERROR",failure)
                    // Transient reconnect failures stay in the journal and UI; only a lasting or user-actionable outage reaches automation.
                    val publish = outage.failed(failure,hasAuthenticated,SystemClock.elapsedRealtime())
                    if (publish.disconnected) app.events.publish(MonitorEventType.TAG_DISCONNECTED,cloudId,null,engine.policy.threshold)
                    if (publish.error) app.events.publish(MonitorEventType.UWB_MONITORING_ERROR,cloudId,null,engine.policy.threshold)
                    if (!publish.disconnected && !publish.error) store.record("OUTAGE_NOT_PUBLISHED","code=$failure; graceMs=${FailurePolicy.GRACE_MILLIS}")
                    store.update { it.copy(measurement=null,stage="연결 중단",detail=failure) }
                } finally {
                    withContext(NonCancellable) {
                        runCatching { transport.deactivate() }.onFailure { store.record("TAG_UWB_STOP_UNCONFIRMED",it.javaClass.simpleName) }
                    }
                    transport.close()
                }
                val retryable = failure != null && FailurePolicy.isRetryable(failure)
                if (!retryable) break
                val backoff = RescanPolicy.delayMillis(retries,app.settings.persisted().rescanMaxMillis)
                retries = (retries + 1).coerceAtMost(4)
                store.update { it.copy(stage="재연결 대기",detail="${backoff / 1000}초 후 내 태그 다시 검색 · 자동 복구 대기",measurement=null) }
                updateNotification("연결 복구 대기 · ${backoff / 1000}초 후 재시도")
                delay(backoff)
            }
        } catch (e: CancellationException) {
            store.record("MONITOR_CANCELLED","user or OS stopped service")
            throw e
        } catch (e: Exception) {
            store.record("MONITOR_ERROR",safe(e))
            app.events.publish(MonitorEventType.UWB_MONITORING_ERROR,cloudId,null,engine.policy.threshold)
            store.update { it.copy(stage="모니터링 오류",detail=safe(e)) }
        } finally {
            settingsJob.cancel()
            // Keep only the last observed state: one small write when tracking ends, not a per-callback history.
            latest.meters?.let { meters -> withContext(NonCancellable) { runCatching { app.settings.lastObservation(meters,latest.atEpochMillis ?: System.currentTimeMillis()) } } }
            val cancelled = !currentCoroutineContext().isActive
            store.update { it.copy(running=false,measurement=null,stage=if (cancelled) "중지됨" else it.stage,
                detail=if (cancelled) "모니터링이 중지됐습니다." else it.detail) }
            store.record("MONITOR_STOP","UWB/GATT released; distance engine receives no disconnect samples")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun safe(error: Exception) = error.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: error.javaClass.simpleName
    private fun updateNotification(text: String) {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(1,notification(text))
        }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this,1,Intent(this,ProbeService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("UWB Pulse 모니터링").setContentText(text).setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).addAction(0,"중지",stop).build()
    }
    override fun onDestroy() { job?.cancel(); scope.cancel(); super.onDestroy() }
    companion object {
        const val ADDRESS = "address"
        const val BACKEND = "backend"
        const val STOP = "dev.uwbpulse.monitor.STOP"
        const val RESUME = "dev.uwbpulse.monitor.RESUME"
        const val CHANNEL = "uwb_probe"
        private const val HIDDEN_UI_MILLIS = 1_000L
        private const val LAST_STATE_MILLIS = 60_000L
        fun ensureNotificationChannel(context: android.content.Context) {
            val channel = NotificationChannel(CHANNEL,"추적 중 알림",NotificationManager.IMPORTANCE_LOW)
            channel.description = "모니터링 서비스 상태 표시. 이 채널을 꺼도 추적은 유지됩니다."
            if (Build.VERSION.SDK_INT >= 33) channel.setBlockable(true)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
