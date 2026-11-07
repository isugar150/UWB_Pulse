package dev.uwbpulse.monitor.debug

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.data.BleDiscovery
import dev.uwbpulse.monitor.data.GattTransport
import dev.uwbpulse.monitor.data.UwbProbe
import dev.uwbpulse.monitor.service.ProbeService
import dev.uwbpulse.monitor.domain.CarModeMachine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Explicit adb-only real-radio experiment. No events, settings writes or HTTP. No credential export. */
class ConnectionBenchmarkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ProbeService.ensureNotificationChannel(this)
        startForeground(91, NotificationCompat.Builder(this,"uwb_probe")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("UWB 연결 시험")
            .setContentText("태그 연결 시간 측정 중 · 자동화 전송 없음").setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (job?.isActive == true) return START_NOT_STICKY
        val mode = intent?.getStringExtra("mode")?.takeIf { it in setOf("baseline", "keep_scan", "auto", "auto_scan", "settle", "native", "native_scan", "delayed", "warm_scan", "warm_cancel", "high", "advertising") } ?: "baseline"
        job = scope.launch {
            val app = application as MonitorApplication
            try {
                // A normal monitor must first be stopped through the existing STOP command.
                check(!app.settings.persisted().monitoringEnabled) { "BENCH_MONITOR_MUST_BE_STOPPED" }
                val cloudId = checkNotNull(app.account.selectedId.value) { "BENCH_NO_SELECTED_TAG" }
                val prefs = app.settings.persisted()
                val origin = SystemClock.elapsedRealtime()
                fun record(code: String, detail: String) = app.store.record("BENCH_$code", "mode=$mode; ms=${SystemClock.elapsedRealtime()-origin}; $detail")
                if (mode == "advertising") {
                    record("START","advertisement intervals only; no connection")
                    withTimeoutOrNull(15_000) {
                        BleDiscovery(this@ConnectionBenchmarkService,::record,app.account::matchAdvertisement).scanDevices(traceAdvertisements=true).collect { }
                    }
                    record("SCAN_CLOSED","no GATT or UWB session opened")
                    return@launch
                }
                val transport = GattTransport(this@ConnectionBenchmarkService, ::record)
                var scan: Job? = null
                record("START", "rate=${prefs.trackingRate}; interactive=${getSystemService(PowerManager::class.java).isInteractive}; activityVisible=${app.store.activityVisible}; no automation")
                try {
                    withTimeout(40_000) {
                        coroutineScope {
                            if (mode == "delayed") delay(CarModeMachine.DISCONNECT_CONFIRM_MILLIS)
                            val found = CompletableDeferred<BleDiscovery.DiscoveredTag>()
                            scan = launch {
                                BleDiscovery(this@ConnectionBenchmarkService,::record,app.account::matchAdvertisement).scanDevices().collect { candidates ->
                                    candidates.firstOrNull { it.candidate.cloudTagId == cloudId && it.candidate.connectable }?.let { found.complete(it) }
                                }
                            }
                            if (mode == "warm_cancel") {
                                delay(500)
                                scan.cancelAndJoin()
                                record("CANCELLED","simulated link recovery; scan closed; no GATT connection")
                                return@coroutineScope
                            }
                            if (mode == "warm_scan") delay(CarModeMachine.DISCONNECT_CONFIRM_MILLIS)
                            val peer = withTimeout(15_000) { found.await() }
                            record("FOUND", "owner advertisement matched; nativeType=${if (android.os.Build.VERSION.SDK_INT >= 37) peer.device.addressType else -1}")
                            if (mode != "keep_scan" && mode != "auto_scan" && mode != "native_scan") scan.cancelAndJoin()
                            if (mode == "settle") delay(250)
                            record("CONNECT_CALL", "auto=${mode == "auto" || mode == "auto_scan"}")
                            if (mode.startsWith("native") || mode == "delayed" || mode == "warm_scan") transport.connect(peer.device)
                            else transport.connect(peer.candidate.address, mode == "auto" || mode == "auto_scan")
                            record("READY", "services discovered")
                            scan.cancelAndJoin()
                            if (mode == "high") transport.highPriority()
                            app.account.credentials(cloudId).use { transport.authenticate(it.masterSecret) }
                            record("AUTH", "mutual proof verified")
                            val sample = withTimeout(20_000) { UwbProbe(this@ConnectionBenchmarkService,::record)
                                .range(prefs.backend,prefs.trackingRate) { transport.activate(it) }.first { it.meters.isFinite() && it.meters >= 0 } }
                            record("FIRST", "meters=${sample.meters}; confidence=${sample.confidence}; interactive=${getSystemService(PowerManager::class.java).isInteractive}; activityVisible=${app.store.activityVisible}")
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        scan?.cancelAndJoin()
                        runCatching { transport.deactivate() }.onFailure { record("STOP_ERROR",it.javaClass.simpleName) }
                        transport.close()
                    }
                    record("CLOSED", "GATT released")
                }
            } catch (_: TimeoutCancellationException) { app.store.record("BENCH_ERROR","mode=$mode; BENCH_TIMEOUT") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val code = e.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: e.javaClass.simpleName
                app.store.record("BENCH_ERROR","mode=$mode; $code")
            } finally {
                app.store.record("BENCH_END","mode=$mode")
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
