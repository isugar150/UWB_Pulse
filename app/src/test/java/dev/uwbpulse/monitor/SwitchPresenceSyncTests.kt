package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.*
import org.junit.Assert.*
import org.junit.Test

class SwitchPresenceSyncTests {
    private val policy = DistancePolicy()
    @Test fun firstNearMeasurementTurnsOnOnceWithoutInventingAReturn() {
        val sync = SwitchPresenceSync(true)
        val engine = DistanceEventEngine(policy)
        assertEquals("on",sync.sample(2.0,engine.away,policy,engine.sample(2.0,0)))
        assertNull(sync.sample(2.0,engine.away,policy,engine.sample(2.0,240)))
    }
    @Test fun firstFarMeasurementWaitsForTheExitDwellThenSendsOffViaEvent() {
        val sync = SwitchPresenceSync(true)
        val engine = DistanceEventEngine(policy)
        for (time in 0L..3_000L step 500) {
            val event = engine.sample(6.0,time)
            assertNull(sync.sample(6.0,engine.away,policy,event))
            if (time < 3_000) assertNull(event)
            else assertEquals("off",SmartThingsSwitch.stateFor(event!!))
        }
    }
    @Test fun restoredAwayStateStaysOffUntilAnActualReturn() {
        val sync = SwitchPresenceSync(true)
        val engine = DistanceEventEngine(policy,initialAway=true)
        assertEquals("off",sync.sample(4.5,engine.away,policy,engine.sample(4.5,0)))
        val event = engine.sample(4.0,240)
        assertEquals("on",SmartThingsSwitch.stateFor(event!!))
        assertNull(sync.sample(4.0,engine.away,policy,event))
    }
    @Test fun lostLinkTurnsOffOnceAndNearAgainTurnsOnOnce() {
        val sync = SwitchPresenceSync(true)
        val engine = DistanceEventEngine(policy)
        assertEquals("on",sync.sample(1.0,engine.away,policy,engine.sample(1.0,0)))
        assertEquals("off",sync.linkLost(engine.away))
        assertTrue(sync.linkLostOff)
        assertNull(sync.linkLost(engine.away)) // one switch change per loss
        // Back in range but not yet inside the return distance: no flapping.
        assertNull(sync.sample(4.8,engine.away,policy,engine.sample(4.8,10_000)))
        assertEquals("on",sync.sample(2.0,engine.away,policy,engine.sample(2.0,10_240)))
        assertFalse(sync.linkLostOff)
        assertNull(sync.sample(2.0,engine.away,policy,engine.sample(2.0,10_480)))
        assertFalse(engine.away) // the distance latch never saw an exit
    }
    @Test fun lostLinkNeverCreatesADistanceEvent() {
        val engine = DistanceEventEngine(policy)
        engine.sample(1.0,0)
        engine.signalLost()
        assertFalse(engine.away)
        assertNull(engine.sample(1.0,60_000)) // reconnect: no exit, no return event
    }
    @Test fun lostLinkIsIgnoredWhenAlreadyAwayDisabledOrSwitchedOff() {
        assertNull(SwitchPresenceSync(true).linkLost(away = true))
        assertNull(SwitchPresenceSync(false).linkLost(away = false))
        assertNull(SwitchPresenceSync(true,offOnLinkLoss = false).linkLost(away = false))
        val sync = SwitchPresenceSync(true)
        sync.offOnLinkLoss(false); assertNull(sync.linkLost(false))
        sync.offOnLinkLoss(true); assertEquals("off",sync.linkLost(false))
    }
    @Test fun anActualDistanceEventTakesOverFromALostLink() {
        val sync = SwitchPresenceSync(true)
        val engine = DistanceEventEngine(policy)
        sync.sample(1.0,engine.away,policy,engine.sample(1.0,0))
        assertEquals("off",sync.linkLost(engine.away))
        // Reconnected far away: the normal exit debounce decides, and the dispatcher sends the event's own "off".
        var event: MonitorEventType? = null
        for (time in 1_000L..4_000L step 500) event = engine.sample(6.0,time).also { assertNull(sync.sample(6.0,engine.away,policy,it)) } ?: event
        assertEquals(MonitorEventType.TAG_DISTANCE_EXCEEDED,event)
        assertFalse(sync.linkLostOff)
        assertNull(sync.linkLost(engine.away)) // engine.away now owns the switch
    }
    @Test fun disablingSmartThingsClearsALostLinkSoTheNextRunReinitializes() {
        val sync = SwitchPresenceSync(true)
        sync.sample(1.0,false,policy,null)
        sync.linkLost(false)
        sync.enabled(false); sync.enabled(true)
        assertFalse(sync.linkLostOff)
        assertEquals("on",sync.sample(1.0,false,policy,null))
    }
    @Test fun enablingRequiresANewValidMeasurementAndReinitializesOnce() {
        val sync = SwitchPresenceSync(false)
        assertNull(sync.sample(2.0,false,policy,null))
        sync.enabled(true)
        assertNull(sync.sample(Double.NaN,false,policy,null))
        assertEquals("on",sync.sample(2.0,false,policy,null))
        sync.enabled(false); sync.enabled(true)
        assertEquals("on",sync.sample(2.0,false,policy,null))
        assertNull(sync.sample(2.0,false,policy,null))
    }
}
