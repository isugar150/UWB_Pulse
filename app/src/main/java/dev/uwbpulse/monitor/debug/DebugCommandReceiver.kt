package dev.uwbpulse.monitor.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.data.CarBluetooth
import dev.uwbpulse.monitor.domain.TrackingRate
import dev.uwbpulse.monitor.service.ProbeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Developer commands for adb, so settings can be changed without driving the screen:
 *
 *   adb shell am broadcast -n dev.uwbpulse.monitor/.debug.DebugCommandReceiver --es tracking_rate NORMAL --ez car_mode true --es monitor start
 *
 * The receiver is exported but guarded by android.permission.DUMP, which only the shell holds: other apps cannot send to it.
 * It changes ordinary settings only. It never reads or writes credentials, tokens, keys or the SmartThings device ID,
 * and it logs names of the settings it changed, never device names or addresses.
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
