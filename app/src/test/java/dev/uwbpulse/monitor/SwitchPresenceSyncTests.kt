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
