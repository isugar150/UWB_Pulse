package dev.uwbpulse.monitor.domain

import kotlinx.coroutines.delay

/**
 * "Turn on, then off" for a departure. Sending only "off" to a switch that is already off changes nothing in SmartThings,
 * so an automation on "switch turns off" would not run again. The pulse guarantees an off transition every time.
 * One request carries both steps so a newer request cannot replace the "on" before it was sent.
 */
object SwitchPulse {
    const val STATE = "pulse"
    const val GAP_MILLIS = 1_500L

    /** Departure signal for the setting: the pulse, or a plain off. */
    fun departureState(pulse: Boolean): String = if (pulse) STATE else "off"

    /**
     * Runs on, waits, off. [send] returns the HTTP status, or null when the request was dropped (expired or replaced).
     * Stops at the first dropped or failed step and returns its status; otherwise the status of the final off.
     */
    suspend fun run(send: suspend (String) -> Int?, gap: suspend () -> Unit = { delay(GAP_MILLIS) }): Int? {
        val on = send("on") ?: return null
        if (on !in 200..299) return on
        gap()
        return send("off")
    }
}
