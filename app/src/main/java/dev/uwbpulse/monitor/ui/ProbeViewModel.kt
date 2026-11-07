package dev.uwbpulse.monitor.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.data.BleDiscovery
import dev.uwbpulse.monitor.domain.Backend
import dev.uwbpulse.monitor.domain.TagCandidate
import dev.uwbpulse.monitor.domain.TrackingRate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ProbeViewModel(application: Application) : AndroidViewModel(application) {
    val store = (application as MonitorApplication).store
    val account = (application as MonitorApplication).account
    val settings = (application as MonitorApplication).settings
    val events = (application as MonitorApplication).events.events
    val webhookUrl = MutableStateFlow((application as MonitorApplication).events.webhook().url)
    val state = store.state
    val log = store.log
    val candidates = MutableStateFlow<List<TagCandidate>>(emptyList())
    val scanning = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val backend = MutableStateFlow(if (Build.VERSION.SDK_INT >= 36) Backend.PLATFORM else Backend.JETPACK)
    init { viewModelScope.launch { backend.value = settings.persisted().backend } }
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
    fun selectOwnedTag(id: String) = viewModelScope.launch { account.selectTag(id) }
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
    fun logout() = viewModelScope.launch { settings.monitoring(false);account.logout() }
    fun resumeAfterBoot(enabled: Boolean) = viewModelScope.launch { settings.resumeAfterBoot(enabled) }
    fun chooseBackend(value: Backend) = viewModelScope.launch { backend.value=value;settings.backend(value) }
}
