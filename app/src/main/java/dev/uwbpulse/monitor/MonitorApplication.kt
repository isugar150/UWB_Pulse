package dev.uwbpulse.monitor

import android.app.Application
import dev.uwbpulse.monitor.data.ProbeStore
import dev.uwbpulse.monitor.data.auth.SamsungAccountRepository
import dev.uwbpulse.monitor.data.MonitorSettings
import dev.uwbpulse.monitor.automation.EventDispatcher

class MonitorApplication : Application() {
    val store by lazy { ProbeStore(this) }
    val account by lazy { SamsungAccountRepository(this).also { it.diagnostic = store::record } }
    val settings by lazy { MonitorSettings(this) }
    val events by lazy { EventDispatcher(this) }
    val smartThings by lazy { dev.uwbpulse.monitor.automation.SmartThingsStore(this) }
}
