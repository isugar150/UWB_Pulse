package dev.uwbpulse.monitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.uwbpulse.monitor.automation.SmartThingsConfig
import dev.uwbpulse.monitor.automation.SmartThingsStore
import dev.uwbpulse.monitor.domain.LatestSwitchDelivery
import dev.uwbpulse.monitor.domain.SwitchRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

/** Uses synthetic data and its own vault/Keystore alias. Never reads the user's account or SmartThings vault. */
@RunWith(AndroidJUnit4::class)
class SwitchVaultDeviceTests {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "review-test-${UUID.randomUUID()}"
    private val store = SmartThingsStore(context,namespace)
    private val request = SwitchRequest("synthetic-event","on",1_000,"synthetic-target")

    @After fun cleanTestVault() {
        store.clear()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("uwbpulse.$namespace.v1") }
    }
    @Test fun pendingRequestSurvivesEncryptedStoreRecreationAndTokenUpdates() {
        store.write(SmartThingsConfig(deviceId="synthetic-target",pendingSwitch=request))
        store.update { it.copy(accessToken="synthetic-token",refreshToken="synthetic-refresh") }
        val restored = SmartThingsStore(context,namespace).read()
        assertEquals(request,restored.pendingSwitch)
        assertEquals("synthetic-token",restored.accessToken)
    }
    @Test fun clearingPendingRequestKeepsConfiguration() {
        store.write(SmartThingsConfig(deviceId="synthetic-target",pendingSwitch=request))
        store.update { it.copy(pendingSwitch=null) }
        val restored = SmartThingsStore(context,namespace).read()
        assertNull(restored.pendingSwitch); assertEquals("synthetic-target",restored.deviceId)
    }
    @Test fun expiredRestoredRequestNeverCallsTransport() = runBlocking {
        store.write(SmartThingsConfig(deviceId="synthetic-target",pendingSwitch=request))
        val restored = SmartThingsStore(context,namespace).read().pendingSwitch!!
        val result = LatestSwitchDelivery().deliver(restored,{ store.read().pendingSwitch },{ 31_000 }) {
            fail("expired persisted automation was sent"); 200
        }
        assertNull(result)
    }
}
