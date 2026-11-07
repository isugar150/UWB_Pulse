package dev.uwbpulse.monitor.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.uwbpulse.monitor.domain.DistancePolicy
import dev.uwbpulse.monitor.domain.MonitorEventType
import dev.uwbpulse.monitor.domain.Backend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*

private val Context.monitorPreferences by preferencesDataStore("monitor_settings")
data class MonitorPreferences(
    val policy: DistancePolicy = DistancePolicy(),
    val notifications: Set<MonitorEventType> = MonitorEventType.entries.toSet(),
    val taskerEnabled: Boolean = false,
    val webhookEnabled: Boolean = false,
    val includeTagId: Boolean = false,
    val monitoringEnabled: Boolean = false,
    val resumeAfterBoot: Boolean = true,
    val backend: Backend = Backend.PLATFORM,
    val awayTagAliases: Set<String> = emptySet(),
)
class MonitorSettings(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val threshold = doublePreferencesKey("threshold")
    private val returned = doublePreferencesKey("returned")
    private val dwell = longPreferencesKey("dwell")
    private val tasker = booleanPreferencesKey("tasker")
    private val webhook = booleanPreferencesKey("webhook")
    private val tagId = booleanPreferencesKey("includeTagId")
    private val monitoring = booleanPreferencesKey("monitoring_enabled")
    private val afterBoot = booleanPreferencesKey("resume_after_boot")
    private val backend = stringPreferencesKey("ranging_backend")
    private val away = stringSetPreferencesKey("away_tag_aliases")
    private fun decode(values: Preferences): MonitorPreferences =
        MonitorPreferences(
            runCatching {
                val original = values[threshold] ?: 5.0
                val limited = original.coerceIn(1.0,10.0)
                val returnValue = if (original != limited) (limited - (original - (values[returned] ?: 4.0))).coerceIn(0.5,limited - 0.5)
                    else (values[returned] ?: 4.0).coerceIn(0.0,limited - 0.5)
                DistancePolicy(limited,returnValue,values[dwell] ?: 3_000)
            }.getOrDefault(DistancePolicy()),
            MonitorEventType.entries.filter { values[booleanPreferencesKey("notify_${it.name}")] != false }.toSet(),
            values[tasker] ?: false, values[webhook] ?: false, values[tagId] ?: false,
            values[monitoring] ?: false,values[afterBoot] ?: true,
            runCatching { Backend.valueOf(values[backend].orEmpty()) }.getOrDefault(if (android.os.Build.VERSION.SDK_INT >= 36) Backend.PLATFORM else Backend.JETPACK),values[away] ?: emptySet())
    val state = context.monitorPreferences.data.map(::decode).stateIn(scope,SharingStarted.Eagerly,MonitorPreferences())
    suspend fun persisted() = decode(context.monitorPreferences.data.first())
    suspend fun setThreshold(value: Double) = context.monitorPreferences.edit {
        val rounded = (kotlin.math.round(value * 2) / 2.0).coerceIn(1.0,10.0)
        val hysteresis = ((it[threshold] ?: 5.0) - (it[returned] ?: 4.0)).coerceAtLeast(0.5)
        it[threshold] = rounded
        it[returned] = (rounded - hysteresis).coerceIn(0.5,rounded - 0.5)
    }
    suspend fun setReturnDistance(value: Double) = context.monitorPreferences.edit { it[returned] = value.coerceIn(0.0,(it[threshold] ?: 5.0) - 0.5) }
    suspend fun setDwell(value: Long) = context.monitorPreferences.edit { it[dwell] = value.coerceIn(500,30_000) }
    suspend fun notify(type: MonitorEventType, enabled: Boolean) = context.monitorPreferences.edit { it[booleanPreferencesKey("notify_${type.name}")] = enabled }
    suspend fun tasker(enabled: Boolean) = context.monitorPreferences.edit { it[tasker] = enabled }
    suspend fun webhook(enabled: Boolean) = context.monitorPreferences.edit { it[webhook] = enabled }
    suspend fun includeTagId(enabled: Boolean) = context.monitorPreferences.edit { it[tagId] = enabled }
    suspend fun monitoring(enabled: Boolean) = context.monitorPreferences.edit { it[monitoring] = enabled }
    suspend fun resumeAfterBoot(enabled: Boolean) = context.monitorPreferences.edit { it[afterBoot] = enabled }
    suspend fun backend(value: Backend) = context.monitorPreferences.edit { it[backend] = value.name }
    suspend fun away(alias: String,value: Boolean) = context.monitorPreferences.edit { it[away] = if (value) (it[away] ?: emptySet()) + alias else (it[away] ?: emptySet()) - alias }
}
