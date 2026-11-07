package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.automation.WebhookConfiguration
import org.junit.Assert.assertThrows
import org.junit.Test

class WebhookPolicyTests {
    @Test fun httpsConfigurationAccepted() { WebhookConfiguration("https://example.com/events","test-token").validate() }
    @Test fun httpAndEmbeddedCredentialsRejected() {
        assertThrows(IllegalArgumentException::class.java) { WebhookConfiguration("http://example.com/events").validate() }
        assertThrows(IllegalArgumentException::class.java) { WebhookConfiguration("https://name:secret@example.com/events").validate() }
    }
    @Test fun headerInjectionAndFragmentsRejected() {
        assertThrows(IllegalArgumentException::class.java) { WebhookConfiguration("https://example.com/events","token\r\nInjected: true").validate() }
        assertThrows(IllegalArgumentException::class.java) { WebhookConfiguration("https://example.com/events#fragment").validate() }
    }
}
