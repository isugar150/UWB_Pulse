package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.data.SmartTagSessionConfig
import dev.uwbpulse.monitor.domain.ReceptionEvidence
import org.junit.Assert.*
import org.junit.Test

class ProbeTests {
    @Test fun packetMatchesPinnedUtagLayout() {
        val config = SmartTagSessionConfig(0x01020304, byteArrayOf(0x11, 0x22), 10)
        val packet = config.payload(byteArrayOf(0x33, 0x44))
        val fixture = "002f0c9c0501020304112233440907d000f0030a010500000000000000000600000000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(fixture, packet)
    }
    @Test fun freshDistanceExpiresWithoutInventingNewEvidence() {
        val evidence = ReceptionEvidence()
        assertFalse(evidence.isFresh(0))
        assertTrue(evidence.accept(5.8, 100, true, true))
        assertTrue(evidence.isFresh(3100))
        assertFalse(evidence.isFresh(3101))
        assertEquals(1L, evidence.count)
        assertEquals(0L, evidence.screenOffCount)
    }
    @Test fun onlyValidActualCallbacksCountAsBackgroundEvidence() {
        val evidence = ReceptionEvidence()
        assertFalse(evidence.accept(Double.NaN, 0, false, false))
        assertFalse(evidence.accept(-1.0, 0, false, false))
        assertFalse(evidence.accept(Double.POSITIVE_INFINITY, 0, false, false))
        assertTrue(evidence.accept(2.0, 100, true, true))
        assertFalse(evidence.accept(2.0, 99, false, false))
        assertTrue(evidence.accept(3.0, 200, false, true))
        assertTrue(evidence.accept(4.0, 300, false, false))
        assertEquals(3L, evidence.count)
        assertEquals(2L, evidence.backgroundCount)
        assertEquals(1L, evidence.screenOffCount)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidAddressRejected() {
        SmartTagSessionConfig(1, byteArrayOf(1), 10)
    }
}
