package dev.uwbpulse.monitor.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.data.BleDiscovery
import dev.uwbpulse.monitor.domain.Backend
import dev.uwbpulse.monitor.domain.TagCandidate
import dev.uwbpulse.monitor.domain.SmartThingsSwitch
import dev.uwbpulse.monitor.domain.TrackingRate
import dev.uwbpulse.monitor.automation.SmartThingsClient
import dev.uwbpulse.monitor.automation.SmartThingsWork
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** What the settings screen may show of the SmartThings secrets: never the secret, the tokens or the device ID value in logs. */
data class SmartThingsView(val connected: Boolean = false, val samsungLogin: Boolean = false, val clientId: String = "", val deviceId: String = "", val redirectUri: String = SmartThingsSwitch.DEFAULT_REDIRECT)

class ProbeViewModel(application: Application) : AndroidViewModel(application) {
    val store = (application as MonitorApplication).store
    val account = (application as MonitorApplication).account
    val settings = (application as MonitorApplication).settings
    val events = (application as MonitorApplication).events.events
    val webhookUrl = MutableStateFlow("")
    val smartThings = MutableStateFlow(SmartThingsView())
    private fun reloadSmartThings() { val c = (getApplication<MonitorApplication>()).smartThings.read(); smartThings.value = SmartThingsView(c.connected,(getApplication<MonitorApplication>()).account.loggedIn(),c.clientId,c.deviceId,c.redirectUri) }
    val state = store.state
    val log = store.log
    val candidates = MutableStateFlow<List<TagCandidate>>(emptyList())
    val scanning = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val backend = MutableStateFlow(if (Build.VERSION.SDK_INT >= 36) Backend.PLATFORM else Backend.JETPACK)
    init {
        viewModelScope.launch { backend.value = settings.persisted().backend }
        // The vault is decrypted through Android Keystore: keep that off the main thread.
        viewModelScope.launch(Dispatchers.IO) { webhookUrl.value = (application as MonitorApplication).events.webhook().url; reloadSmartThings() }
    }
    val selected = store.selectedAddress.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var scanJob: Job? = null
    fun select(candidate: TagCandidate) { viewModelScope.launch { store.selectAddress(candidate.address) } }
    fun stopScan() { scanJob?.cancel() }
    fun scan() {
        if (scanning.value) return
        scanJob = viewModelScope.launch {
            scanning.value = true; error.value = null; candidates.value = emptyList()
            store.record("BLE_SCAN_START", "FD5A filter; 15 seconds; RSSI not used")
            try {
                withTimeoutOrNull(15_000) {
                    BleDiscovery(getApplication(), store::record, account::matchAdvertisement).scan().collect { found ->
                        candidates.value = found
                        found.singleOrNull { it.cloudTagId == account.selectedId.value && it.cloudTagId != null }?.let {
                            if (selected.value != it.address) store.selectAddress(it.address)
                        }
                    }
                }
                if (candidates.value.isEmpty()) error.value = "후보가 없습니다. 태그가 근처에 있는지 확인하세요. 기존 SmartThings 연결·광고 정책으로 검색되지 않을 수도 있습니다."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error.value = e.message?.takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: e.javaClass.simpleName
                store.record("BLE_SCAN_ERROR", error.value!!)
            } finally {
                scanning.value = false
                store.record("BLE_SCAN_STOP", "candidates=${candidates.value.size}; ownership unverified")
            }
        }
    }
    fun login(open: (String) -> Unit) = viewModelScope.launch {
        try { open(account.loginUrl()) }
        catch (e: Exception) { error.value = dev.uwbpulse.monitor.data.auth.SamsungAccountRepository.safeError(e) }
    }
    fun refreshAccount() = viewModelScope.launch {
        try { account.refresh() }
        catch (e: Exception) { error.value = dev.uwbpulse.monitor.data.auth.SamsungAccountRepository.safeError(e) }
    }
    fun selectOwnedTag(id: String) = viewModelScope.launch {
        if (account.selectedId.value != id) settings.clearLastObservation() // the last distance belongs to the previous tag
        account.selectTag(id)
    }
    fun threshold(value: Float) = viewModelScope.launch { settings.setThreshold(value.toDouble()) }
    fun returnDistance(value: Float) = viewModelScope.launch { settings.setReturnDistance(kotlin.math.round(value * 2.0) / 2.0) }
    fun dwell(value: Float) = viewModelScope.launch { settings.setDwell((kotlin.math.round(value * 2) * 500).toLong()) }
    fun trackingRate(value: TrackingRate) = viewModelScope.launch { settings.trackingRate(value) }
    fun rescanMaxMillis(value: Long) = viewModelScope.launch { settings.rescanMaxMillis(value) }
    fun notification(type: dev.uwbpulse.monitor.domain.MonitorEventType, enabled: Boolean) = viewModelScope.launch { settings.notify(type,enabled) }
    fun tasker(enabled: Boolean) = viewModelScope.launch { settings.tasker(enabled) }
    fun webhook(enabled: Boolean) = viewModelScope.launch { settings.webhook(enabled) }
    fun includeTagId(enabled: Boolean) = viewModelScope.launch { settings.includeTagId(enabled) }
    fun saveWebhook(url: String, token: String, clearToken: Boolean) = viewModelScope.launch {
        try { (getApplication<MonitorApplication>()).events.saveWebhook(url,token,clearToken); webhookUrl.value = url.trim(); error.value = "Webhook 설정을 암호화해 저장했습니다." }
        catch (e: Exception) { error.value = dev.uwbpulse.monitor.data.auth.SamsungAccountRepository.safeError(e) }
    }
    fun saveSmartThings(clientId: String,clientSecret: String,deviceId: String,redirectUri: String,code: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val store = (getApplication<MonitorApplication>()).smartThings
            require(clientId.isNotBlank() || code.isBlank()) { "SMARTTHINGS_CLIENT_ID_REQUIRED" }
            require(deviceId.isBlank() || SmartThingsSwitch.isDeviceId(deviceId.trim())) { "SMARTTHINGS_DEVICE_ID_INVALID" }
            require(redirectUri.trim().startsWith("https://")) { "SMARTTHINGS_REDIRECT_HTTPS_REQUIRED" }
            store.update { it.copy(clientId=clientId.trim(),clientSecret=clientSecret.ifBlank { it.clientSecret },deviceId=deviceId.trim(),redirectUri=redirectUri.trim()) }
            if (code.isNotBlank()) SmartThingsClient.exchangeCode(store,code)
            reloadSmartThings()
            error.value = if (smartThings.value.connected) "SmartThings 설정을 암호화해 저장했습니다." else "저장했습니다. 승인 코드를 붙여 넣어 연결하세요."
        } catch (e: Exception) { error.value = dev.uwbpulse.monitor.data.auth.SamsungAccountRepository.safeError(e) }
    }
    fun smartThingsEnabled(enabled: Boolean) = viewModelScope.launch {
        val view = smartThings.value
        if (enabled && ((!view.connected && !view.samsungLogin) || !SmartThingsSwitch.isDeviceId(view.deviceId))) { error.value = "삼성 계정 로그인과 가상 스위치 기기 ID가 필요합니다."; return@launch }
        settings.smartThings(enabled); SmartThingsWork.syncKeepAlive(getApplication(),enabled)
        if (!enabled) SmartThingsWork.cancelPending(getApplication())
    }
    fun testSmartThings(on: Boolean) = viewModelScope.launch {
        val view = smartThings.value
        if ((!view.connected && !view.samsungLogin) || !SmartThingsSwitch.isDeviceId(view.deviceId)) { error.value = "삼성 계정 로그인과 가상 스위치 기기 ID가 필요합니다."; return@launch }
        SmartThingsWork.sendState(getApplication(),if (on) "on" else "off",test=true)
        error.value = "시험 신호를 보냈습니다. 결과는 진단 로그의 ST_SWITCH_RESULT에서 확인하세요."
    }
    /** Statuses only: tells whether the stored Samsung login can talk to the SmartThings API without any extra credential. */
    fun probeSmartThingsLogin() = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<MonitorApplication>()
        try {
            val result = dev.uwbpulse.monitor.automation.SmartThingsProbe.run(app.account,app.smartThings.read().deviceId)
            app.store.record("ST_PROBE","readStatus=${result.readStatus}; writeStatus=${result.writeStatus}")
            error.value = "로그인 토큰 시험: 읽기 ${result.readStatus} · 쓰기 ${result.writeStatus ?: "-"}"
        } catch (e: Exception) {
            val code = dev.uwbpulse.monitor.data.auth.SamsungAccountRepository.safeError(e)
            app.store.record("ST_PROBE_ERROR",code); error.value = "로그인 토큰 시험 실패: $code"
        }
    }
    fun disconnectSmartThings() = viewModelScope.launch(Dispatchers.IO) {
        (getApplication<MonitorApplication>()).smartThings.clear(); settings.smartThings(false); SmartThingsWork.syncKeepAlive(getApplication(),false); reloadSmartThings()
    }
    fun logout() = viewModelScope.launch { settings.monitoring(false);settings.clearLastObservation();account.logout() }
    fun resumeAfterBoot(enabled: Boolean) = viewModelScope.launch { settings.resumeAfterBoot(enabled) }
    fun chooseBackend(value: Backend) = viewModelScope.launch { backend.value=value;settings.backend(value) }
}
