package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.CarLink
import dev.uwbpulse.monitor.domain.CarLink.Profile
import dev.uwbpulse.monitor.domain.CarModeMachine
import dev.uwbpulse.monitor.domain.CarRestore
import dev.uwbpulse.monitor.domain.CarModeMachine.Action
import dev.uwbpulse.monitor.domain.CarModeMachine.Phase
import org.junit.Assert.*
import org.junit.Test

class CarModeTests {
    @Test fun speculativeScanRequiresAnEarlierCarLinkAndStopsBeingEligibleOnceTracking() {
        val machine = CarModeMachine()
        assertFalse(machine.canPrepareDisconnect)
        machine.car(false)
        assertFalse(machine.canPrepareDisconnect)
        machine.car(true)
        assertTrue(machine.canPrepareDisconnect)
        machine.car(false)
        assertFalse(machine.canPrepareDisconnect)
        machine.departed()
        assertFalse(machine.canPrepareDisconnect)
    }
    @Test fun speculativeScanOnRestoreRequiresFreshEvidenceOfTheCarLink() {
        val fresh = CarRestore.start("CAR_CONNECTED",1_000,2_000)
        assertTrue(CarModeMachine(fresh.phase,fresh.carWasLinked).canPrepareDisconnect)
        val old = CarRestore.start("CAR_CONNECTED",1_000,1_000+CarRestore.FRESH_MILLIS+1)
        assertFalse(CarModeMachine(old.phase,old.carWasLinked).canPrepareDisconnect)
        val waiting = CarRestore.start("WAITING",1_000,2_000)
        assertFalse(CarModeMachine(waiting.phase,waiting.carWasLinked).canPrepareDisconnect)
    }
    @Test fun carIsConnectedWhileAnyLinkIsUp() {
        val link = CarLink()
        assertFalse(link.connected)
        assertEquals(true, link.acl(true))
        assertNull(link.profile(Profile.A2DP, true)) // already connected through the ACL link
        assertTrue(link.connected)
    }
    @Test fun lostAclLinkEndsEveryProfileEvenIfTheirBroadcastsWereMissed() {
        val link = CarLink()
        link.acl(true); link.profile(Profile.A2DP, true); link.profile(Profile.HEADSET, true)
        assertEquals(false, link.acl(false))
        assertFalse(link.connected)
    }
    @Test fun profilesAloneKeepTheCarConnectedUntilBothDrop() {
        val link = CarLink()
        assertEquals(true, link.profile(Profile.HEADSET, true))
        assertNull(link.profile(Profile.A2DP, true))
        assertNull(link.profile(Profile.HEADSET, false)) // audio still up
        assertEquals(false, link.profile(Profile.A2DP, false))
    }
    @Test fun seedReflectsTheProfilesThatAreConnectedNow() {
        val link = CarLink()
        assertTrue(link.seed(a2dpUp = false, headsetUp = true))
        assertFalse(CarLink().seed(false, false))
        // The seeded profile still ends with its own disconnect.
        assertEquals(false, link.profile(Profile.HEADSET, false))
    }
    @Test fun repeatedIdenticalEventsReportNoChange() {
        val link = CarLink()
        assertEquals(true, link.acl(true))
        assertNull(link.acl(true))
        assertEquals(false, link.acl(false))
        assertNull(link.acl(false))
    }

    @Test fun startingWithTheCarLinkedIdlesAndStartingWithoutItOnlyWaits() {
        assertEquals(Action.ENTER_CAR, CarModeMachine().car(true))
        val machine = CarModeMachine()
        // No earlier car link in this run: that is not "the user left the car", so the tag is not touched.
        assertEquals(Action.WAIT, machine.car(false))
        assertEquals(Phase.WAITING, machine.phase)
        assertEquals(Action.NONE, machine.car(false))
        assertEquals(Action.NONE, machine.departed())
        // The first car link ends the wait; a later drop is a real departure and tracks.
        assertEquals(Action.ENTER_CAR, machine.car(true))
        assertEquals(Action.START_TRACKING, machine.car(false))
    }
    @Test fun leavingTheCarStartsOneWindowAndADepartureEndsIt() {
        val machine = CarModeMachine()
        machine.car(true)
        assertEquals(Action.START_TRACKING, machine.car(false))
        assertEquals(Action.NONE, machine.car(false)) // a second drop notice changes nothing
        assertEquals(Action.STOP_TRACKING, machine.departed())
        assertEquals(Phase.AWAY, machine.phase)
        assertEquals(Action.NONE, machine.departed())  // decided once
        assertEquals(Action.NONE, machine.car(false))  // no window restarts while away
    }
    @Test fun theNextCarLinkAlwaysWinsAndStartsOver() {
        val machine = CarModeMachine()
        machine.car(true); machine.car(false); machine.departed()
        assertEquals(Action.ENTER_CAR, machine.car(true))
        assertEquals(Action.START_TRACKING, machine.car(false))
        // Car links again while the window is still measuring (a short Bluetooth glitch).
        assertEquals(Action.ENTER_CAR, machine.car(true))
        assertEquals(Phase.CAR_CONNECTED, machine.phase)
        assertEquals(Action.NONE, machine.departed()) // nothing to stop once the car is back
    }
    @Test fun aDepartureOutsideAWindowIsIgnored() {
        val machine = CarModeMachine()
        assertEquals(Action.NONE, machine.departed())
        machine.car(true)
        assertEquals(Action.NONE, machine.departed())
    }
    @Test fun aRecentSavedCarLinkMeansTheRestartMayHaveMissedTheDeparture() {
        val now = 1_000_000_000L
        val fresh = CarRestore.start("CAR_CONNECTED", now - 60_000, now)
        assertEquals(Phase.UNKNOWN, fresh.phase); assertTrue(fresh.carWasLinked)
        assertTrue(CarRestore.start("TRACKING", now - CarRestore.FRESH_MILLIS, now).carWasLinked)
        // An old save, a missing save, a future timestamp (clock change) or an unknown name decide nothing.
        assertFalse(CarRestore.start("CAR_CONNECTED", now - CarRestore.FRESH_MILLIS - 1, now).carWasLinked)
        assertFalse(CarRestore.start("CAR_CONNECTED", 0, now).carWasLinked)
        assertFalse(CarRestore.start("CAR_CONNECTED", now + 5_000, now).carWasLinked)
        assertFalse(CarRestore.start("", now, now).carWasLinked)
        assertFalse(CarRestore.start("garbage", now, now).carWasLinked)
        assertFalse(CarRestore.start("WAITING", now - 1_000, now).carWasLinked)
    }
    @Test fun aSavedDepartureStaysADepartureHoweverOldAndIdlesUntilTheCarLinks() {
        val start = CarRestore.start("AWAY", 1, 5_000_000_000L)
        assertEquals(Phase.AWAY, start.phase); assertFalse(start.carWasLinked)
        val machine = CarModeMachine(start.phase, start.carWasLinked)
        assertEquals(Action.NONE, machine.car(false))          // still away: nothing to track
        assertEquals(Action.ENTER_CAR, machine.car(true))
    }
    @Test fun restartAfterAMissedDropOpensAWindowAndAnUnchangedCarLinkJustIdles() {
        val missed = CarRestore.start("CAR_CONNECTED", 1_000, 2_000)
        assertEquals(Action.START_TRACKING, CarModeMachine(missed.phase, missed.carWasLinked).car(false))
        assertEquals(Action.ENTER_CAR, CarModeMachine(missed.phase, missed.carWasLinked).car(true))
        // No save at all: a car-less start only waits, as before.
        val none = CarRestore.start("", 0, 2_000)
        assertEquals(Action.WAIT, CarModeMachine(none.phase, none.carWasLinked).car(false))
    }
    @Test fun heartbeatIsMuchShorterThanTheFreshnessLimit() {
        assertTrue(CarRestore.HEARTBEAT_MILLIS * 5 <= CarRestore.FRESH_MILLIS)
    }
    @Test fun debounceIsShorterThanThePresenceWindow() {
        assertTrue(CarModeMachine.DISCONNECT_CONFIRM_MILLIS < dev.uwbpulse.monitor.domain.PresencePolicy.LOST_AFTER_MILLIS)
    }
}
