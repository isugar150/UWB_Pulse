package dev.uwbpulse.monitor.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.uwbpulse.monitor.domain.ProbeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.OffsetDateTime
import java.util.ArrayDeque

private val Context.preferences by preferencesDataStore("probe")

class ProbeStore(private val context: Context) {
    private val selectedKey = stringPreferencesKey("selected_ble_address")
    val selectedAddress = context.preferences.data.map { it[selectedKey] }
    suspend fun selectAddress(address: String) { context.preferences.edit { it[selectedKey] = address } }
    private val mutable = MutableStateFlow(ProbeState())
    val state = mutable.asStateFlow()
    fun update(block: (ProbeState) -> ProbeState) { mutable.update(block) }
    @Volatile var activityVisible = false
    private val lines = ArrayDeque<String>()
    private val mutableLog = MutableStateFlow<List<String>>(emptyList())
    val log = mutableLog.asStateFlow()
    private val journal = File(context.filesDir, "probe-journal.txt")
    private var fileLines = 0
    init {
        synchronized(lines) {
            if (journal.exists()) journal.readLines().takeLast(MAX_LINES).forEach(lines::addLast)
            fileLines = lines.size
            // Normalise once so later records can be appended instead of rewriting the whole file.
            if (lines.isNotEmpty()) journal.writeText(lines.joinToString("\n", postfix = "\n"))
        }
        record("APP_START", "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}; build ${Build.DISPLAY}; mode=OWNER_AUTHENTICATED_UWB")
    }
    @Synchronized fun record(code: String, detail: String) {
        // Callers pass only controlled status codes; never MACs, cloud IDs, keys or tokens.
        val line = "${OffsetDateTime.now()} elapsed=${android.os.SystemClock.elapsedRealtime()} $code $detail"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
            mutableLog.value = lines.toList()
            // Append one line; compact the file only when it has grown to twice the retained size.
            if (++fileLines > 2 * MAX_LINES) { journal.writeText(lines.joinToString("\n", postfix = "\n")); fileLines = lines.size }
            else journal.appendText(line + "\n")
        }
    }
    fun export(): File {
        val folder = File(context.cacheDir, "reports").apply { mkdirs() }
        return File(folder, "smarttag-probe.txt").apply {
            writeText("SmartTag UWB PoC — device evidence, not certification\n" +
                "Current build: owner mutual authentication and encrypted UWB OOB.\n" +
                "Phase 1/2 require actual device evidence. Historical logs can precede the latest APP_START.\n" +
                "Reception counters are in the UWB_REPORT journal lines.\n\n" +
                synchronized(lines) { lines.joinToString("\n") })
        }
    }
    private companion object { const val MAX_LINES = 300 }
}
