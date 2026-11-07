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
import dev.uwbpulse.monitor.data.BondedDevice
import dev.uwbpulse.monitor.data.CarBluetooth
import dev.uwbpulse.monitor.data.GattTransport
import dev.uwbpulse.monitor.data.UwbProbe
import dev.uwbpulse.monitor.automation.MonitorEvent
import dev.uwbpulse.monitor.automation.SmartThingsWork
import dev.uwbpulse.monitor.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class ProbeService : Service() {
    private val app get() = application as MonitorApplication
    private val store get() = app.store
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var notificationText: String? = null
    private val power by lazy { getSystemService(PowerManager::class.java) }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            scope.launch { app.settings.monitoring(false);app.settings.carPhase("",0);stopSelf() } // a user stop forgets the car phase: only a crash or restart may resume it
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_STICKY
        val manualStart = intent != null && intent.action != RESUME
        val requestedBackend = runCatching { Backend.valueOf(intent?.getStringExtra(BACKEND) ?: "") }.getOrNull()
        try {
            ensureNotificationChannel(this)
            startForeground(1,notification("내 태그 연결 중"),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            notificationText = "내 태그 연결 중"
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
    private suspend fun deviceFor(cloudId: String, attempt: Int, showStage: Boolean = true,
                                  confirmation: Deferred<Unit>? = null): BleDiscovery.DiscoveredTag {
        if (showStage) store.update { it.copy(stage="내 태그 검색",detail="소유자 광고 서명을 확인하고 있습니다.",measurement=null) }
        return try {
            withTimeout(15_000) {
                BleDiscovery(this@ProbeService,store::record,app.account::matchAdvertisement)
                    .scanDevices(RescanPolicy.balancedScan(attempt,store.activityVisible))
                    .map { candidates -> candidates.firstOrNull { it.candidate.cloudTagId == cloudId && it.candidate.connectable } }
                    .filterNotNull().map { peer -> confirmation?.await(); peer }.first()
            }
        } catch (_: TimeoutCancellationException) { error("OWNER_TAG_NOT_FOUND") }
    }
    /** Service-level run: continuous tracking, or car-Bluetooth mode when a car device is chosen. Owns the service shutdown. */
    private suspend fun monitor(cloudId: String,backend: Backend) {
        val initial = app.settings.persisted()
        SmartThingsWork.syncKeepAlive(this,initial.smartThingsEnabled) // idempotent; keeps the SmartThings refresh token alive
        val car = initial.carDevice
        store.update { ProbeState(running=true,stage="연결 준비",detail=if (car != null) "차량 블루투스 상태 확인 중" else "소유자 상호 인증 및 암호화 UWB 설정") }
        store.record("MONITOR_START","backend=$backend; rate=${initial.trackingRate}; rescanMaxMs=${initial.rescanMaxMillis}; carMode=${car != null}; interactive=${power.isInteractive}; batteryExempt=${power.isIgnoringBatteryOptimizations(packageName)}")
        try {
            if (car == null) track(cloudId,backend,null) else carMonitor(cloudId,backend,car)
        } finally {
            val cancelled = !currentCoroutineContext().isActive
            store.update { it.copy(running=false,measurement=null,stage=if (cancelled) "중지됨" else it.stage,
                detail=if (cancelled) "모니터링이 중지됐습니다." else it.detail) }
            store.record("MONITOR_STOP","UWB/GATT released; distance engine receives no disconnect samples")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** One tracking window of the car mode: when it decides the user walked away it calls [onDeparted] once and the window is stopped. */
    private class CarWindow(val anchorElapsedMillis: Long,val preparedPeer: Deferred<Result<BleDiscovery.DiscoveredTag>>?,val onDeparted: () -> Unit)
    private sealed interface CarSignal {
        data class Link(val connected: Boolean) : CarSignal
        data class WindowEnded(val id: Int) : CarSignal
    }

    /**
     * Car-Bluetooth mode: while the car is linked the tag is not scanned, connected or ranged (no UWB on the tag).
     * When the car link drops for good, one tracking window runs until the departure is decided; then everything stops
     * until the car links again, which turns the switch on. Distance events still come only from real UWB samples.
     */
    private suspend fun carMonitor(cloudId: String,backend: Backend,car: BondedDevice) = coroutineScope {
        val alias = MonitorEvent.tagAlias(packageName,cloudId)
        val bluetooth = CarBluetooth(this@ProbeService,store::record)
        val link = CarLink()
        val saved = app.settings.persisted()
        val start = CarRestore.start(saved.carPhase,saved.carPhaseAt,System.currentTimeMillis())
        val machine = CarModeMachine(start.phase,start.carWasLinked)
        store.record("CAR_RESTORE","saved=${saved.carPhase.ifBlank { "none" }}; ageMs=${if (saved.carPhaseAt > 0) System.currentTimeMillis() - saved.carPhaseAt else -1}; carWasLinkedBeforeStart=${start.carWasLinked}; startPhase=${start.phase}")
        suspend fun persistPhase() = app.settings.carPhase(machine.phase.name,System.currentTimeMillis())
        val heartbeat = launch {
            while (isActive) {
                delay(CarRestore.HEARTBEAT_MILLIS)
                if (machine.phase == CarModeMachine.Phase.CAR_CONNECTED || machine.phase == CarModeMachine.Phase.TRACKING) persistPhase()
            }
        }
        val signals = Channel<CarSignal>(Channel.UNLIMITED)
        var window: Job? = null
        var windowId = 0
        val unregister = bluetooth.observe(car.address,link) { signals.trySend(CarSignal.Link(it)) }
        try {
            if (machine.phase == CarModeMachine.Phase.AWAY) {
                store.update { it.copy(running=true,stage="차량을 떠남 · 대기",detail="차량 블루투스가 다시 연결되면 재개합니다.",measurement=null,lastReportAgeMillis=null) }
                updateNotification("차량을 떠남 · 재연결 대기")
            }
            val seeded = bluetooth.connectedNow(car.address,link)
            store.record("CAR_LINK","connected=${seeded ?: "unknown"}; via=seed")
            signals.trySend(CarSignal.Link(seeded ?: false))
            for (signal in signals) {
                when (signal) {
                    is CarSignal.Link -> {
                        // Judge the live state, not the signal that woke us: a "connected" signal may already be stale, and any
                        // reading of "lost" must hold for a while before it starts a window (a flapping profile is not a departure).
                        var linked = link.connected
                        var prepared: Deferred<Result<BleDiscovery.DiscoveredTag>>? = null
                        var preparationConfirmed: CompletableDeferred<Unit>? = null
                        if (!linked && machine.canPrepareDisconnect) {
                            store.record("CAR_PRE_SCAN","scan overlaps the 3 s confirmation; no GATT or events before confirmation")
                            val confirmed = CompletableDeferred<Unit>()
                            preparationConfirmed = confirmed
                            prepared = async {
                                try { Result.success(deviceFor(cloudId,0,showStage=false,confirmation=confirmed)) }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { Result.failure(e) }
                            }
                        }
                        try {
                            if (!linked) {
                                delay(CarModeMachine.DISCONNECT_CONFIRM_MILLIS)
                                linked = link.connected
                            }
                            when (machine.car(linked)) {
                                CarModeMachine.Action.ENTER_CAR -> {
                                    window?.cancelAndJoin(); window = null
                                    app.settings.away(alias,false) // the car link, not a distance, says the user is back
                                    app.events.syncSmartThings("on")
                                    persistPhase()
                                    store.record("CAR_PHASE","car linked; tag left alone; switch=on")
                                    store.update { it.copy(running=true,stage="차량 연결됨 · 대기",detail="차량 블루투스가 연결돼 있어 태그를 측정하지 않습니다.",measurement=null,lastReportAgeMillis=null) }
                                    updateNotification("차량 연결됨 · 태그 대기")
                                }
                                CarModeMachine.Action.START_TRACKING -> {
                                    val id = ++windowId
                                    val anchor = SystemClock.elapsedRealtime()
                                    preparationConfirmed?.complete(Unit)
                                    persistPhase()
                                    store.record("CAR_PHASE",if (start.carWasLinked && id == 1) "restart: the car link was up before the restart and is gone now; tracking window $id started" else "car link lost; tracking window $id started")
                                    store.update { it.copy(running=true,stage="차량 연결 해제",detail="태그에 연결해 거리를 확인합니다.",measurement=null) }
                                    val preparedPeer = prepared
                                    window = launch {
                                        try {
                                            track(cloudId,backend,CarWindow(anchor,preparedPeer) {
                                                if (machine.departed() == CarModeMachine.Action.STOP_TRACKING) window?.cancel()
                                            })
                                        } finally { preparedPeer?.cancel(); signals.trySend(CarSignal.WindowEnded(id)) }
                                    }
                                    prepared = null // the window now owns cancellation of this scan
                                }
                                CarModeMachine.Action.WAIT -> {
                                    persistPhase()
                                    store.record("CAR_PHASE","started without the car link; tag left alone until the car links")
                                    store.update { it.copy(running=true,stage="차량 연결 대기",detail="차량 블루투스가 연결될 때까지 태그를 측정하지 않습니다.",measurement=null,lastReportAgeMillis=null) }
                                    updateNotification("차량 연결 대기 · 태그 쉼")
                                }
                                else -> Unit
                            }
                        } finally {
                            if (prepared != null) {
                                prepared.cancelAndJoin()
                                store.record("CAR_PRE_SCAN_CANCELLED","car link recovered or confirmation cancelled; no tag connection")
                            }
                        }
                    }
                    is CarSignal.WindowEnded -> {
                        if (signal.id != windowId) continue
                        when (machine.phase) {
                            CarModeMachine.Phase.AWAY -> {
                                persistPhase()
                                store.record("CAR_PHASE","departure decided; tag released until the car links again")
                                store.update { it.copy(running=true,stage="차량을 떠남 · 대기",detail="차량 블루투스가 다시 연결되면 재개합니다.",measurement=null,lastReportAgeMillis=null) }
                                updateNotification("차량을 떠남 · 재연결 대기")
                            }
                            CarModeMachine.Phase.TRACKING -> { store.record("CAR_PHASE","tracking window ended without a decision; stopping"); break }
                            else -> Unit
                        }
                    }
                }
            }
        } finally {
            unregister(); heartbeat.cancel()
            withContext(NonCancellable) { window?.cancelAndJoin() }
            signals.close()
        }
    }

    private suspend fun track(cloudId: String,backend: Backend,car: CarWindow?) {
        val initial = app.settings.persisted()
        val alias = MonitorEvent.tagAlias(packageName,cloudId)
        // The ranging interval is part of the encrypted tag configuration, so it is fixed for this service run.
        val rate = initial.trackingRate
        val engine = DistanceEventEngine(initial.policy,rate.maxGapMillis,initialAway=alias in initial.awayTagAliases)
        val evidence = ReceptionEvidence(rate.staleAfterMillis)
        val switchSync = SwitchPresenceSync(initial.smartThingsEnabled,initial.offOnLinkLoss)
        val settingsJob = scope.launch { app.settings.state.collect { engine.configure(it.policy); switchSync.enabled(it.smartThingsEnabled); switchSync.offOnLinkLoss(it.offOnLinkLoss) } }
        var latest = LastObservation()
        var retries = 0
        var hasAuthenticated = false
        val outage = OutageGate()
        // The last real distance and whether the current outage is a lost link (not a radio the user switched off).
        var lastValidAt: Long? = null // continuous mode only: the last real distance
        val judge = car?.let { DepartureJudge(it.anchorElapsedMillis) } // car window: counts from the car link drop until the first distance
        var linkLossOutage = false
        var uwbRefusals = 0 // authenticated, but the tag would not start UWB, in a row
        var uwbNoticeSent = false
        var departureHandled = false
        var preparedPeer = car?.preparedPeer // first attempt only: a reconnect always uses a fresh advertisement
        /** Finish a lost-tag window; an unmeasured tag needs explicit opt-in before any switch signal. */
        fun departure(reason: String,silentMillis: Long) {
            if (departureHandled) return
            departureHandled = true
            val signalAllowed = judge?.shouldSignalDeparture(SystemClock.elapsedRealtime(),app.settings.state.value.carUnmeasuredDeparture) ?: true
            if (!signalAllowed) store.record("CAR_UNMEASURED_NO_SIGNAL","reason=$reason; silentMs=$silentMillis; window ended without changing the switch")
            if (signalAllowed && switchSync.linkLost(engine.away) != null) {
                store.record("PRESENCE_LINK_LOST","reason=$reason; silentMs=$silentMillis; measuredInWindow=${judge?.measured}; car=${car != null}; distance events are not created")
                app.events.departed()
                if (car != null && judge?.measured != true) noticeNoTag(if (uwbRefusals >= FailurePolicy.UWB_REFUSALS_BEFORE_NOTICE) NoTagNotice.UWB_REFUSED else NoTagNotice.NOT_MEASURED)
            }
            car?.onDeparted?.invoke() // decided even when nothing is sent (SmartThings off)
        }
        val presenceJob = scope.launch {
            var tickAt = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1_000)
                val now = SystemClock.elapsedRealtime()
                // With the screen off the CPU may sleep and coroutine timers pause: log it so a late lock is explainable.
                if (now - tickAt > TICK_GAP_LOG_MILLIS) store.record("TICK_DELAYED","gapMs=${now - tickAt}; interactive=${power.isInteractive}")
                tickAt = now
                val bluetoothOn = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
                if (judge != null) { if (judge.lost(now,bluetoothOn)) departure("silence",judge.silentMillis(now)) }
                else {
                    val silent = lastValidAt?.let { now - it }
                    if (PresencePolicy.isLost(silent,linkLossOutage,bluetoothOn)) departure("silence",silent ?: 0)
                }
            }
        }
        try {
            check(packageManager.hasSystemFeature("android.hardware.uwb")) { "NO_UWB_HARDWARE" }
            while (currentCoroutineContext().isActive) {
                val transport = GattTransport(this,store::record)
                var failure: String? = null
                var retry = false
                var authenticatedNow = false
                var reportedNow = false
                try {
                    val prepared = preparedPeer.also { preparedPeer = null }
                    val peer = prepared?.await()?.getOrThrow() ?: deviceFor(cloudId,retries)
                    coroutineScope {
                        launch { val status = transport.disconnected.await(); error("GATT_DISCONNECTED_$status") }
                        transport.connect(peer.device)
                        store.update { it.copy(stage="소유자 인증",detail="태그의 암호학적 응답 확인 중") }
                        app.account.credentials(cloudId).use { transport.authenticate(it.masterSecret) }
                        hasAuthenticated = true; authenticatedNow = true
                        evidence.beginSession()
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
                        var lowPower = false
                        UwbProbe(this@ProbeService,store::record).range(backend,rate) { transport.activate(it) }.collect { measurement ->
                            val now = measurement.elapsedMillis
                            if (!evidence.accept(measurement.meters,now,store.activityVisible,power.isInteractive)) return@collect
                            retries = 0; outage.recovered()
                            lastValidAt = now; linkLossOutage = false; reportedNow = true; uwbRefusals = 0; uwbNoticeSent = false
                            judge?.measurement(now)
                            if (!lowPower) { lowPower = true; transport.lowPower() }
                            val event = engine.sample(measurement.meters,now)
                            event?.let {
                                app.settings.away(alias,engine.away)
                                app.events.publish(it,cloudId,measurement.meters,engine.policy.threshold)
                            }
                            val wasLinkLost = switchSync.linkLostOff
                            switchSync.sample(measurement.meters,engine.away,engine.policy,event)?.let {
                                if (wasLinkLost) store.record("PRESENCE_RESTORED","meters=${measurement.meters}; switch=$it")
                                app.events.syncSmartThings(it)
                            }
                            // Car mode: a real exit, a restored away latch or a lost tag means the departure is decided; stop measuring.
                            if (car != null && (engine.away || switchSync.linkLostOff)) { departureHandled = true; car.onDeparted() }
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
                    linkLossOutage = FailurePolicy.isLinkLoss(failure)
                    if (authenticatedNow && !reportedNow && FailurePolicy.isUwbRefused(failure)) uwbRefusals++
                    if (uwbRefusals >= FailurePolicy.UWB_REFUSALS_BEFORE_NOTICE && !uwbNoticeSent) {
                        uwbNoticeSent = true
                        store.record("TAG_UWB_UNAVAILABLE","refusals=$uwbRefusals; code=$failure; BLE and owner authentication work, the tag does not start UWB; check the tag battery")
                        noticeNoTag(NoTagNotice.UWB_REFUSED,standalone = car == null)
                    }
                    engine.signalLost()
                    store.record("MONITOR_CONNECTION_ERROR",failure)
                    // Transient reconnect failures stay in the journal and UI; only a lasting or user-actionable outage reaches automation.
                    val publish = outage.failed(failure,hasAuthenticated,SystemClock.elapsedRealtime())
                    retry = publish.retry
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
                if (!retry) {
                    // Keep the service for the next drive. Unmeasured failures obey the same opt-in and minimum wait as the timer.
                    if (car != null) departure("window_failed:${failure ?: "unknown"}",judge?.silentMillis(SystemClock.elapsedRealtime()) ?: 0)
                    break
                }
                val backoff = FailurePolicy.backoffAfterRefusals(RescanPolicy.delayMillis(retries,app.settings.state.value.rescanMaxMillis),uwbRefusals)
                retries = (retries + 1).coerceAtMost(4)
                store.update { it.copy(stage="재연결 대기",detail="${backoff / 1000}초 후 내 태그 다시 검색 · 자동 복구 대기",measurement=null) }
                updateNotification("연결 복구 대기 · ${backoff / 1000}초 후 재시도")
                delay(backoff)
            }
        } catch (e: CancellationException) {
            store.record(if (car == null) "MONITOR_CANCELLED" else "CAR_TRACKING_STOPPED","user, OS or the car link stopped tracking")
            throw e
        } catch (e: Exception) {
            store.record("MONITOR_ERROR",safe(e))
            app.events.publish(MonitorEventType.UWB_MONITORING_ERROR,cloudId,null,engine.policy.threshold)
            store.update { it.copy(stage="모니터링 오류",detail=safe(e)) }
        } finally {
            settingsJob.cancel(); presenceJob.cancel()
            // Keep only the last observed state: one small write when tracking ends, not a per-callback history.
            latest.meters?.let { meters -> withContext(NonCancellable) { runCatching { app.settings.lastObservation(meters,latest.atEpochMillis ?: System.currentTimeMillis()) } } }
        }
    }
    private enum class NoTagNotice { NOT_MEASURED, UWB_REFUSED }
    /**
     * Say why the switch went off, or why UWB is not working, so a weak tag battery is not a mystery.
     * [standalone] = a warning only (continuous mode): the switch was not touched.
     */
    private fun noticeNoTag(kind: NoTagNotice,standalone: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NOTICE_CHANNEL,"태그 상태 안내",NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this,2,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val (title,text) = when {
            kind == NoTagNotice.UWB_REFUSED -> "태그가 UWB를 시작하지 못합니다" to "블루투스 인증은 되지만 UWB가 켜지지 않습니다. 태그 배터리가 부족할 수 있으니 확인하거나 교체하세요."
            else -> "스위치를 껐습니다" to "차량 연결이 끊긴 뒤 ${PresencePolicy.NEVER_MEASURED_MILLIS / 1000}초 동안 태그를 측정하지 못했습니다. 태그 배터리와 연결을 확인하세요."
        }
        manager.notify(if (kind == NoTagNotice.UWB_REFUSED) NOTICE_ID_UWB else NOTICE_ID,NotificationCompat.Builder(this,NOTICE_CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    }
    private fun safe(error: Exception) = error.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: error.javaClass.simpleName
    private fun updateNotification(text: String) {
        if (text == notificationText) return
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(1,notification(text))
            notificationText = text
        }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this,1,Intent(this,ProbeService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("UWB Pulse 모니터링").setContentText(text).setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).setOnlyAlertOnce(true).addAction(0,"중지",stop).build()
    }
    override fun onDestroy() { job?.cancel(); scope.cancel(); super.onDestroy() }
    companion object {
        const val ADDRESS = "address"
        const val BACKEND = "backend"
        const val STOP = "dev.uwbpulse.monitor.STOP"
        const val RESUME = "dev.uwbpulse.monitor.RESUME"
        const val CHANNEL = "uwb_probe"
        private const val NOTICE_CHANNEL = "uwb_no_tag_notice"
        private const val NOTICE_ID = 3
        private const val NOTICE_ID_UWB = 4
        private const val TICK_GAP_LOG_MILLIS = 5_000L
        private const val HIDDEN_UI_MILLIS = 1_000L
        private const val LAST_STATE_MILLIS = 60_000L
        fun ensureNotificationChannel(context: android.content.Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Existing importance belongs to the user (including a restored hidden channel).
            if (manager.getNotificationChannel(CHANNEL) != null) return
            val channel = NotificationChannel(CHANNEL,"추적 중 알림",NotificationManager.IMPORTANCE_LOW)
            channel.description = "모니터링 서비스 상태 표시. 이 채널을 꺼도 추적은 유지됩니다."
            if (Build.VERSION.SDK_INT >= 33) channel.setBlockable(true)
            manager.createNotificationChannel(channel)
        }
    }
}
