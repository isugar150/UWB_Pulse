package dev.uwbpulse.monitor.domain

enum class Backend { PLATFORM, JETPACK }
data class TagCandidate(val address: String, val label: String, val connectable: Boolean, val advertisesUwb: Boolean?, val cloudTagId: String? = null)
data class Measurement(
    val meters: Double,
    val elapsedMillis: Long,
    val confidence: Int? = null,
)
data class ProbeState(
    val running: Boolean = false,
    val stage: String = "준비",
    val detail: String = "내 태그를 선택하고 모니터링을 시작하세요.",
    val measurement: Measurement? = null,
    val lastReportAgeMillis: Long? = null,
)
/** Last distance the service saw and when; written sparingly so tracking does not keep waking storage. */
data class LastObservation(val meters: Double? = null, val atEpochMillis: Long? = null)

/** Pure reception evidence, never treats an old reading as a current distance. Counters are journal-only developer evidence. */
class ReceptionEvidence(private val staleAfterMillis: Long = 3_000) {
    private var previous: Long? = null
    var count = 0L; private set
    var backgroundCount = 0L; private set
    var screenOffCount = 0L; private set
    /** A new radio session gets its own first-report deadline; keep only the run's diagnostic counters. */
    fun beginSession() { previous = null }
    fun accept(value: Double, now: Long, foreground: Boolean, interactive: Boolean): Boolean {
        if (!value.isFinite() || value < 0 || (previous != null && now < previous!!)) return false
        previous = now
        count++
        if (!foreground) backgroundCount++
        if (!interactive) screenOffCount++
        return true
    }
    fun age(now: Long): Long? = previous?.let { (now - it).coerceAtLeast(0) }
    fun isFresh(now: Long): Boolean = age(now)?.let { it <= staleAfterMillis } ?: false
}
