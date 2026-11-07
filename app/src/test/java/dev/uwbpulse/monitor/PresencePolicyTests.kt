package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.PresencePolicy
import org.junit.Assert.*
import org.junit.Test

class PresencePolicyTests {
    private val after = PresencePolicy.LOST_AFTER_MILLIS

    @Test fun lostOnlyAfterTheSilenceWindowOfALostLink() {
        assertFalse(PresencePolicy.isLost(after - 1, linkLossOutage = true, bluetoothOn = true))
        assertTrue(PresencePolicy.isLost(after, linkLossOutage = true, bluetoothOn = true))
        assertTrue(PresencePolicy.isLost(10 * after, linkLossOutage = true, bluetoothOn = true))
    }
    @Test fun aTagThatWasNeverMeasuredDecidesNothing() {
        assertFalse(PresencePolicy.isLost(null, linkLossOutage = true, bluetoothOn = true))
    }
    @Test fun radiosTheUserSwitchedOffAreNotAWalkAway() {
        assertFalse(PresencePolicy.isLost(10 * after, linkLossOutage = false, bluetoothOn = true))
        assertFalse(PresencePolicy.isLost(10 * after, linkLossOutage = true, bluetoothOn = false))
    }
    @Test fun carWindowWaitsShortAfterATagWasSeenAndLongerWhenItNeverWas() {
        val seen = PresencePolicy.SEEN_THEN_LOST_MILLIS
        val never = PresencePolicy.NEVER_MEASURED_MILLIS
        assertFalse(PresencePolicy.isLostInCarWindow(seen - 1, measuredInWindow = true, bluetoothOn = true))
        assertTrue(PresencePolicy.isLostInCarWindow(seen, measuredInWindow = true, bluetoothOn = true))
        assertFalse(PresencePolicy.isLostInCarWindow(never - 1, measuredInWindow = false, bluetoothOn = true))
        assertTrue(PresencePolicy.isLostInCarWindow(never, measuredInWindow = false, bluetoothOn = true))
        assertTrue(seen < never)
    }
    @Test fun carWindowIgnoresTheFailureKindButNotABluetoothTheUserSwitchedOff() {
        // No link-loss classification is needed: a dropped car link already says the user is leaving.
        assertFalse(PresencePolicy.isLostInCarWindow(10 * PresencePolicy.NEVER_MEASURED_MILLIS, measuredInWindow = false, bluetoothOn = false))
        assertFalse(PresencePolicy.isLostInCarWindow(10 * PresencePolicy.NEVER_MEASURED_MILLIS, measuredInWindow = true, bluetoothOn = false))
    }
    @Test fun neverMeasuredWindowCoversTwoConnectionAttemptsButIsShorterThanTheExternalGracePeriod() {
        assertTrue(PresencePolicy.NEVER_MEASURED_MILLIS >= 45_000)
        assertTrue(PresencePolicy.NEVER_MEASURED_MILLIS < dev.uwbpulse.monitor.domain.FailurePolicy.GRACE_MILLIS)
    }
    @Test fun windowCoversAnOrdinaryReconnectButIsShorterThanTheExternalGracePeriod() {
        assertTrue(after >= 20_000)
        assertTrue(after < dev.uwbpulse.monitor.domain.FailurePolicy.GRACE_MILLIS)
    }
}
