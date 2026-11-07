package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.MonitorEventType
import dev.uwbpulse.monitor.domain.SmartThingsSwitch
import org.junit.Assert.*
import org.junit.Test

class SmartThingsSwitchTests {
    private val device = "123e4567-e89b-12d3-a456-426614174000"

    @Test fun onlyExitAndReturnMoveTheSwitch() {
        assertEquals("on", SmartThingsSwitch.stateFor(MonitorEventType.TAG_DISTANCE_EXCEEDED))
        assertEquals("off", SmartThingsSwitch.stateFor(MonitorEventType.TAG_RETURNED))
        assertNull(SmartThingsSwitch.stateFor(MonitorEventType.TAG_DISCONNECTED))
        assertNull(SmartThingsSwitch.stateFor(MonitorEventType.UWB_MONITORING_ERROR))
    }
    @Test fun eventRequestHasTheDocumentedShape() {
        assertEquals("https://api.smartthings.com/virtualdevices/$device/events", SmartThingsSwitch.eventsUrl(device))
        assertEquals("""{"deviceEvents":[{"component":"main","capability":"switch","attribute":"switch","value":"on"}]}""", SmartThingsSwitch.eventsBody("on"))
        assertTrue(SmartThingsSwitch.eventsBody("off").endsWith(""""value":"off"}]}"""))
    }
    @Test fun invalidDeviceIdAndStateAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SmartThingsSwitch.eventsUrl("../../devices") }
        assertThrows(IllegalArgumentException::class.java) { SmartThingsSwitch.eventsUrl("$device/commands") }
        assertThrows(IllegalArgumentException::class.java) { SmartThingsSwitch.eventsBody("toggle") }
        assertFalse(SmartThingsSwitch.isDeviceId(""))
        assertTrue(SmartThingsSwitch.isDeviceId(device.uppercase()))
    }
    @Test fun defaultRedirectNeverReachesAThirdPartyServer() {
        assertTrue(SmartThingsSwitch.DEFAULT_REDIRECT.startsWith("https://") && SmartThingsSwitch.DEFAULT_REDIRECT.substringAfter("://").substringBefore('/').endsWith(".invalid"))
    }
    @Test fun clientAuthenticationUsesHttpBasic() {
        assertEquals("Basic YWJjOmRlZg==", SmartThingsSwitch.basicAuth("abc", "def"))
    }
    @Test fun formsAndAuthorizationUrlAreEncoded() {
        assertEquals("grant_type=refresh_token&client_id=a%26b&refresh_token=t%2Bk%3D", SmartThingsSwitch.refreshForm("a&b", "t+k="))
        val form = SmartThingsSwitch.codeExchangeForm("id", "c o/d", "https://httpbin.org/get")
        assertTrue(form.contains("code=c+o%2Fd") && form.contains("redirect_uri=https%3A%2F%2Fhttpbin.org%2Fget"))
        val url = SmartThingsSwitch.authorizationUrl("id", "https://httpbin.org/get")
        assertTrue(url.startsWith("https://api.smartthings.com/oauth/authorize?client_id=id&response_type=code"))
        assertTrue(url.contains("scope=r%3Adevices%3A*+w%3Adevices%3A*+x%3Adevices%3A*"))
    }
    @Test fun tokenResponseIsParsedAndTheRefreshTokenIsOptional() {
        val full = SmartThingsSwitch.parseTokens("""{"access_token":"A1","token_type":"bearer","refresh_token":"R1","expires_in":86399,"scope":"x"}""")
        assertEquals(SmartThingsSwitch.Tokens("A1", "R1", 86399), full)
        val noRefresh = SmartThingsSwitch.parseTokens("""{ "access_token" : "A2" }""")
        assertEquals("A2", noRefresh.accessToken); assertNull(noRefresh.refreshToken); assertEquals(3600L, noRefresh.expiresInSeconds)
        assertThrows(IllegalArgumentException::class.java) { SmartThingsSwitch.parseTokens("""{"error":"invalid_grant"}""") }
    }
    @Test fun tokenIsRenewedBeforeItExpires() {
        val now = 1_000_000L
        assertTrue(SmartThingsSwitch.needsRenewal("", now + 3_600_000, now))
        assertTrue(SmartThingsSwitch.needsRenewal("t", now + SmartThingsSwitch.RENEW_MARGIN_MILLIS - 1, now))
        assertFalse(SmartThingsSwitch.needsRenewal("t", now + SmartThingsSwitch.RENEW_MARGIN_MILLIS, now))
    }
}
