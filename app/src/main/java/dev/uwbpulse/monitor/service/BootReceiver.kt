package dev.uwbpulse.monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.uwbpulse.monitor.MonitorApplication
import dev.uwbpulse.monitor.domain.ResumePolicy
import dev.uwbpulse.monitor.domain.ResumeReason
import kotlinx.coroutines.*

/** Credential-protected account keys are used only after the first user unlock. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val reason = when(intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ResumeReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ResumeReason.PACKAGE_UPDATE
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val app = context.applicationContext as MonitorApplication
            try {
                val preferences = app.settings.persisted()
                if (!ResumePolicy.shouldResume(preferences.monitoringEnabled,preferences.resumeAfterBoot,reason)) return@launch
                app.store.record("AUTO_RESUME_REQUEST","reason=${reason.name}; saved monitoring state enabled")
                context.startForegroundService(Intent(context,ProbeService::class.java).setAction(ProbeService.RESUME))
            } catch (e: Exception) { app.store.record("AUTO_RESUME_BLOCKED",e.javaClass.simpleName) }
            finally { pending.finish();coroutineContext.cancel() }
        }
    }
}
