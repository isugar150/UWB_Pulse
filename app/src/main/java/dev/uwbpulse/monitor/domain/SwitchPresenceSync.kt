package dev.uwbpulse.monitor.domain

/** One initial state from real ranging per enable/run, without inventing an exit/return event. */
class SwitchPresenceSync(private var enabled: Boolean) {
    private var synchronized = false
    fun enabled(value: Boolean) {
        if (value != enabled) { enabled = value; synchronized = false }
    }
    fun sample(meters: Double, away: Boolean, policy: DistancePolicy, event: MonitorEventType?): String? {
        if (!enabled || !meters.isFinite() || meters < 0) return null
        if (event != null) { synchronized = true; return null } // The event dispatcher sends the transition itself.
        if (synchronized || (!away && meters > policy.threshold)) return null // Wait for the exit debounce.
        synchronized = true
        return if (away) "off" else "on"
    }
}
