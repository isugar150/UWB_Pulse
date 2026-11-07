package dev.uwbpulse.monitor.domain

/**
 * The car-window departure decision on a monotonic clock. Evidence that the tag is near is a real UWB distance only;
 * until the first one, silence is counted from the moment the car link dropped (the anchor). Pure, so whole timelines
 * (leave, door closes, tag never found, Bluetooth switched off) can be replayed in unit tests.
 */
class DepartureJudge(anchorElapsedMillis: Long) {
    private var evidenceAt = anchorElapsedMillis
    /** True once the tag was measured near in this window. */
    var measured = false; private set

    fun measurement(nowElapsedMillis: Long) { measured = true; evidenceAt = nowElapsedMillis }
    fun silentMillis(nowElapsedMillis: Long): Long = (nowElapsedMillis - evidenceAt).coerceAtLeast(0)
    fun lost(nowElapsedMillis: Long, bluetoothOn: Boolean): Boolean =
        PresencePolicy.isLostInCarWindow(silentMillis(nowElapsedMillis), measured, bluetoothOn)

    /** An unmeasured window still ends after a minute, but sends a switch signal only by explicit opt-in. */
    fun shouldSignalDeparture(nowElapsedMillis: Long, onNeverMeasured: Boolean = false): Boolean =
        measured || (onNeverMeasured && silentMillis(nowElapsedMillis) >= PresencePolicy.NEVER_MEASURED_MILLIS)
}
