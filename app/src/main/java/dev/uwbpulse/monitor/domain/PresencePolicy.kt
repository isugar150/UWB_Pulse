package dev.uwbpulse.monitor.domain

/**
 * When a vanished tag counts as "walked away" for the presence switch.
 *
 * A closed car door blocks the radio: the last real distance is small and no later report can exceed the threshold,
 * so the distance engine alone never reports an exit. Distance events (Tasker, Webhook, notifications) still come
 * only from real UWB samples; this rule drives nothing but the SmartThings switch.
 */
object PresencePolicy {
    /**
     * No valid distance for this long, while the link is lost, means the tag is out of reach.
     * Longer than an ordinary reconnect (about 10-15 s) so a short dropout does not flip the switch.
     */
    const val LOST_AFTER_MILLIS = 30_000L

    /** Car window, the tag was measured near and then went silent: it just vanished, so a short wait is enough. */
    const val SEEN_THEN_LOST_MILLIS = 8_000L

    /** End a never-measured car window after a minute. Sending a switch signal is a separate opt-in. */
    const val NEVER_MEASURED_MILLIS = 60_000L

    /**
     * Car-Bluetooth window: the car link dropping already says the user is leaving, so silence counts from the window start
     * (or the last tag distance) whatever the failure was. Only a Bluetooth switched off by the user is excluded.
     */
    fun isLostInCarWindow(silentMillis: Long, measuredInWindow: Boolean, bluetoothOn: Boolean): Boolean =
        bluetoothOn && silentMillis >= (if (measuredInWindow) SEEN_THEN_LOST_MILLIS else NEVER_MEASURED_MILLIS)

    /**
     * [millisSinceValid] is null until the first real distance of this run, so a tag that was never seen decides nothing.
     * [linkLossOutage] is false for user-actionable causes (Bluetooth or UWB switched off), and [bluetoothOn]
     * guards the Bluetooth case when the last failure was reported as a plain disconnect.
     */
    fun isLost(millisSinceValid: Long?, linkLossOutage: Boolean, bluetoothOn: Boolean): Boolean =
        millisSinceValid != null && millisSinceValid >= LOST_AFTER_MILLIS && linkLossOutage && bluetoothOn
}
