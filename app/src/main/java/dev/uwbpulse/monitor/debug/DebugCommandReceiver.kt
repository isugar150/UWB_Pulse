package dev.uwbpulse.monitor.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.data.CarBluetooth
import dev.uwbpulse.monitor.domain.TrackingRate
import dev.uwbpulse.monitor.service.ProbeService
import dev.uwbpulse.monitor.automation.SmartThingsHttp
import dev.uwbpulse.monitor.domain.SmartThingsSwitch
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Developer commands for adb, so settings can be changed without driving the screen:
 *
 *   adb shell am broadcast -n dev.uwbpulse.monitor/.debug.DebugCommandReceiver --es tracking_rate NORMAL --ez car_mode true --es monitor start
 *
 * The receiver is exported but guarded by android.permission.DUMP, which only the shell holds: other apps cannot send to it.
 * Recovery can select a unique bonded car-audio device and find an existing switch by exact name using
 * the app's normal account access. Credentials and IDs never leave the app or enter the journal.
 * Logs contain setting names, never device names or addresses.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as MonitorApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val applied = mutableListOf<String>()
            try {
                val extras = intent.extras
                extras?.getString("tracking_rate")?.let { app.settings.trackingRate(TrackingRate.parse(it)); applied += "tracking_rate=${TrackingRate.parse(it)}" }
                if (extras?.containsKey("return_distance") == true) {
                    app.settings.setReturnDistance(extras.getFloat("return_distance").toDouble()); applied += "return_distance=set"
                }
                if (extras?.getBoolean("recover_car") == true) {
                    check(context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "BLUETOOTH_PERMISSION_REQUIRED" }
                    val devices = app.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
                        .filter { it.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO }
                    val device = devices.singleOrNull()
                    if (device != null) {
                        app.settings.car(dev.uwbpulse.monitor.data.BondedDevice(device.name ?: "차량",device.address))
                        applied += "car_device=recovered"
                    } else applied += "car_device=NEEDS_SELECTION"
                }
                extras?.getString("recover_switch_name")?.let { label ->
                    fun read(renew: Boolean) = SmartThingsHttp.get("https://${SmartThingsSwitch.API_HOST}/v1/devices",
                        mapOf("Authorization" to "Bearer ${app.account.accessToken(renew)}"))
                    var response = read(false)
                    if (response.status == 401) response = read(true)
                    check(response.status in 200..299) { "SWITCH_RECOVERY_HTTP_${response.status}" }
                    val items = JSONObject(response.body).getJSONArray("items")
                    val matches = (0 until items.length()).map { items.getJSONObject(it) }
                        .filter { it.optString("label") == label || it.optString("name") == label }
                    val deviceId = matches.singleOrNull()?.optString("deviceId").orEmpty()
                    check(SmartThingsSwitch.isDeviceId(deviceId)) { "SWITCH_RECOVERY_NEEDS_SELECTION" }
                    app.smartThings.update { it.copy(deviceId=deviceId) }
                    applied += "smartthings_device=recovered"
                }
                extras?.getString("car_device")?.let { name ->
                    val device = CarBluetooth(app) { _, _ -> }.bonded().firstOrNull { it.name == name }
                    if (device != null) { app.settings.car(device); applied += "car_device=set" } else applied += "car_device=NOT_FOUND"
                }
                fun flag(key: String, action: suspend (Boolean) -> Unit) {
                    if (extras?.containsKey(key) == true) { val value = extras.getBoolean(key); kotlinx.coroutines.runBlocking { action(value) }; applied += "$key=$value" }
                }
                flag("car_mode") { app.settings.carMode(it) }
                flag("smartthings") { app.settings.smartThings(it) }
                flag("off_on_link_loss") { app.settings.offOnLinkLoss(it) }
                flag("pulse") { app.settings.pulseOnDeparture(it) }
                flag("car_unmeasured_departure") { app.settings.carUnmeasuredDeparture(it) }
                if (extras?.getBoolean("status") == true) {
                    val saved = app.settings.persisted()
                    val importance = context.getSystemService(android.app.NotificationManager::class.java)
                        .getNotificationChannel(ProbeService.CHANNEL)?.importance
                    app.store.record("DEBUG_SETTINGS","monitoring=${saved.monitoringEnabled}; rate=${saved.trackingRate}; " +
                        "threshold=${saved.policy.threshold}; returned=${saved.policy.returnDistance}; dwell=${saved.policy.dwellMillis}; " +
                        "carMode=${saved.carMode}; carConfigured=${saved.carDevice != null}; smartthings=${saved.smartThingsEnabled}; " +
                        "switchConfigured=${SmartThingsSwitch.isDeviceId(app.smartThings.read().deviceId)}; selectedTag=${app.account.selectedId.value != null}; " +
                        "resume=${saved.resumeAfterBoot}; offOnLinkLoss=${saved.offOnLinkLoss}; pulse=${saved.pulseOnDeparture}; carUnmeasuredDeparture=${saved.carUnmeasuredDeparture}; " +
                        "notifications=${saved.notifications.size}; channelImportance=$importance")
                }
                extras?.getString("monitor")?.let { command ->
                    val result = runCatching {
                        when (command) {
                            "start" -> context.startForegroundService(Intent(context, ProbeService::class.java))
                            "stop" -> context.startService(Intent(context, ProbeService::class.java).setAction(ProbeService.STOP))
                            else -> error("UNKNOWN_MONITOR_COMMAND")
                        }
                    }
                    applied += "monitor=$command:${if (result.isSuccess) "sent" else result.exceptionOrNull()?.javaClass?.simpleName}"
                }
            } catch (e: Exception) {
                applied += "error=${e.javaClass.simpleName}"
            } finally {
                app.store.record("DEBUG_COMMAND", applied.joinToString("; ").ifBlank { "nothing" })
                pending.finish()
            }
        }
    }
}
