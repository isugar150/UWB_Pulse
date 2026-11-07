package dev.uwbpulse.monitor

import dev.uwbpulse.monitor.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SwitchDeliveryTests {
    private val request = SwitchRequest("event-a","on",1_000,"synthetic-target")

    @Test fun expiresAtThirtySecondsAndRejectsClockRollback() {
        assertTrue(SwitchDeliveryPolicy.isFresh(request,30_999))
        assertFalse(SwitchDeliveryPolicy.isFresh(request,31_000))
        assertFalse(SwitchDeliveryPolicy.isFresh(request,999))
        assertFalse(SwitchDeliveryPolicy.isFresh(request.copy(createdAtEpochMillis=0),1_000))
    }
    @Test fun expiredRequestNeverReachesTheNetwork() = runBlocking {
        assertNull(LatestSwitchDelivery().deliver(request,{ request },{ 31_000 }) { fail("expired request sent"); 200 })
    }
    @Test fun replacedRequestNeverReachesTheNetwork() = runBlocking {
        assertNull(LatestSwitchDelivery().deliver(request,{ request.copy(id="event-b",state="off") },{ 1_000 }) { fail("old request sent"); 200 })
    }
    @Test fun changedTargetOrClearedQueuePreventsDelivery() = runBlocking {
        val sender = LatestSwitchDelivery()
        assertNull(sender.deliver(request,{ request.copy(targetDeviceId="different-target") },{ 1_000 }) { fail(); 200 })
        assertNull(sender.deliver(request,{ null },{ 1_000 }) { fail(); 200 })
    }
    @Test fun expiryDuringTokenRefreshPreventsThePost() = runBlocking {
        var now = 1_000L
        var posts = 0
        val result = LatestSwitchDelivery().deliver(request,{ request },{ now }) { guard ->
            now = 31_000 // Simulated token refresh took the entire validity window.
            guard(); posts++; 200
        }
        assertNull(result); assertEquals(0,posts)
    }
    @Test fun replacedRequestCannotResendAfterUnauthorizedResponse() = runBlocking {
        var latest = request
        var posts = 0
        val result = LatestSwitchDelivery().deliver(request,{ latest },{ 1_000 }) { guard ->
            guard(); posts++ // First POST returned 401.
            latest = request.copy(id="event-b",state="off")
            guard(); posts++; 200 // The refreshed retry must not run.
        }
        assertNull(result); assertEquals(1,posts)
    }
    @Test fun replacementWaitsForCancelledInFlightHttpToActuallyReturn() = runBlocking {
        withTimeout(5_000) {
            val sender = LatestSwitchDelivery()
            var latest = request
            val entered = CompletableDeferred<Unit>()
            val releaseHttp = CompletableDeferred<Unit>()
            val sent = mutableListOf<String>()
            val first = launch {
                sender.deliver(request,{ latest },{ 1_000 }) { guard ->
                    guard(); sent += "on-start"; entered.complete(Unit)
                    withContext(NonCancellable) { releaseHttp.await(); sent += "on-end" }
                    200
                }
            }
            entered.await()
            latest = request.copy(id="event-b",state="off")
            first.cancel()
            val next = latest
            val second = launch(start=CoroutineStart.UNDISPATCHED) {
                sender.deliver(next,{ latest },{ 1_000 }) { guard -> guard(); sent += "off"; 200 }
            }
            assertEquals(listOf("on-start"),sent)
            releaseHttp.complete(Unit)
            first.join(); second.join()
            assertEquals(listOf("on-start","on-end","off"),sent)
        }
    }
    @Test fun requestThatExpiresWhileWaitingForPreviousHttpIsDropped() = runBlocking {
        withTimeout(5_000) {
            val sender = LatestSwitchDelivery()
            var now = 1_000L
            var latest = request
            val entered = CompletableDeferred<Unit>()
            val releaseHttp = CompletableDeferred<Unit>()
            val first = launch { sender.deliver(request,{ latest },{ now }) { entered.complete(Unit); releaseHttp.await(); 200 } }
            entered.await()
            latest = request.copy(id="event-b",state="off")
            val next = latest
            val second = async(start=CoroutineStart.UNDISPATCHED) {
                sender.deliver(next,{ latest },{ now }) { fail("request expired while waiting"); 200 }
            }
            now = 31_000; releaseHttp.complete(Unit)
            first.join(); assertNull(second.await())
        }
    }
    @Test fun freshRequestReturnsHttpStatus() = runBlocking {
        assertEquals(200,LatestSwitchDelivery().deliver(request,{ request },{ 1_000 }) { guard -> guard(); 200 })
    }
    @Test fun requestDescriptionNeverExposesTarget() {
        assertEquals("SwitchRequest(REDACTED)",request.toString())
    }
}
