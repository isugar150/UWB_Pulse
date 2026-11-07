package dev.uwbpulse.monitor.domain

/**
 * Presence-switch decisions that are not distance events: one initial state from real ranging per enable/run,
 * and one "off" when a tag that was in range stays unreachable (see [PresencePolicy]).
 * Neither invents an exit or return event; the distance engine and its away latch are never touched.
 */
class SwitchPresenceSync(private var enabled: Boolean, private var offOnLinkLoss: Boolean = true) {
    private var synchronized = false
    private var lostOff = false
    /** True from the "off" sent for a lost link until the tag is measured near again. */
    val linkLostOff: Boolean get() = lostOff
    fun enabled(value: Boolean) {
        if (value != enabled) { enabled = value; synchronized = false; lostOff = false }
    }
    fun offOnLinkLoss(value: Boolean) { offOnLinkLoss = value }
    /** The tag has been unreachable long enough: "off" once, and only if the engine does not already consider it away. */
    fun linkLost(away: Boolean): String? {
        if (!enabled || !offOnLinkLoss || away || lostOff) return null
        lostOff = true; synchronized = true
        return "off"
    }
    fun sample(meters: Double, away: Boolean, policy: DistancePolicy, event: MonitorEventType?): String? {
        if (!enabled || !meters.isFinite() || meters < 0) return null
        if (event != null) { synchronized = true; lostOff = false; return null } // The event dispatcher sends the transition itself.
        if (away) lostOff = false // The distance latch owns the switch again.
        if (lostOff) {
            if (meters > policy.returnDistance) return null // Same hysteresis as a real return.
            lostOff = false; synchronized = true
            return "on"
        }
        if (synchronized || (!away && meters > policy.threshold)) return null // Wait for the exit debounce.
        synchronized = true
        return if (away) "off" else "on"
    }
}
