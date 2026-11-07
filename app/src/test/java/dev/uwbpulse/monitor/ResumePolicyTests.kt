package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.*
import org.junit.Assert.*
import org.junit.Test

class ResumePolicyTests {
    @Test fun bootRestoresOnlyPreviouslyEnabledMonitoring() {
        assertTrue(ResumePolicy.shouldResume(true,true,ResumeReason.BOOT))
        assertFalse(ResumePolicy.shouldResume(false,true,ResumeReason.BOOT))
    }
    @Test fun bootOptOutDoesNotDisableProcessRecovery() {
        assertFalse(ResumePolicy.shouldResume(true,false,ResumeReason.BOOT))
        assertTrue(ResumePolicy.shouldResume(true,false,ResumeReason.PROCESS_RESTART))
        assertTrue(ResumePolicy.shouldResume(true,false,ResumeReason.PACKAGE_UPDATE))
    }
    @Test fun manualStopIsNeverOverridden() {
        ResumeReason.entries.forEach { assertFalse(ResumePolicy.shouldResume(false,true,it)) }
    }
    @Test fun maximumThresholdIsTenMeters() {
        assertEquals(10.0,DistancePolicy(10.0,9.0).threshold,0.0)
        assertThrows(IllegalArgumentException::class.java) { DistancePolicy(10.5,9.0) }
    }
}
