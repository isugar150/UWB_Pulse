/* SPDX-License-Identifier: GPL-3.0-only
 * Session setup adapted from uTag JetpackUwbRepository and PlatformUwbRepository.
 * Copyright Kieron Quinn; see THIRD_PARTY_NOTICES.md for pinned sources and changes.
 */
package dev.uwbpulse.monitor.data

import android.content.Context
import android.os.Build
import android.ranging.*
import android.ranging.raw.RawRangingDevice
import android.ranging.raw.RawResponderRangingConfig
import android.ranging.uwb.UwbAddress
import android.ranging.uwb.UwbComplexChannel
import android.ranging.uwb.UwbRangingParams
import androidx.annotation.RequiresApi
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import dev.uwbpulse.monitor.domain.Backend
import dev.uwbpulse.monitor.domain.Measurement
import dev.uwbpulse.monitor.domain.TrackingRate
import dev.uwbpulse.monitor.domain.connectionTimeout
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

class UwbProbe(private val context: Context, private val record: (String, String) -> Unit) {
    /** configureTag requires a real authenticated, encrypted OOB implementation. */
    fun range(backend: Backend, requestedRate: TrackingRate, configureTag: suspend (ByteArray) -> Unit): Flow<Measurement> =
        if (backend == Backend.PLATFORM) {
            if (Build.VERSION.SDK_INT >= 36) platformRange(requestedRate, configureTag)
            else error("PLATFORM_REQUIRES_API_36")
        } else jetpackRange(requestedRate, configureTag)

    /** A non-default rate is used only when the device lists it; the tag interval always follows the phone's effective rate. */
    private fun effectiveRate(requested: TrackingRate, supported: Collection<Int>): TrackingRate {
        val effective = if (requested == TrackingRate.NORMAL || requested.platformRate in supported) requested else TrackingRate.NORMAL
        record("UWB_RATE", "requested=$requested; effective=$effective; intervalMs=${effective.intervalMillis}; supported=${supported.sorted()}")
        return effective
    }

    private fun jetpackRange(requestedRate: TrackingRate, configureTag: suspend (ByteArray) -> Unit): Flow<Measurement> = flow {
        val manager = UwbManager.createInstance(context)
        check(manager.isAvailable()) { "UWB_DISABLED_OR_UNAVAILABLE" }
        val session = manager.controleeSessionScope()
        record("JETPACK_CAPABILITIES", "background=${session.rangingCapabilities.isBackgroundRangingSupported}")
        val rate = effectiveRate(requestedRate, session.rangingCapabilities.supportedRangingUpdateRates)
        val config = SmartTagSessionConfig.create()
        val parameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId = config.sessionId,
            subSessionId = 0,
            sessionKeyInfo = config.sessionKey,
            subSessionKeyInfo = null,
            complexChannel = androidx.core.uwb.UwbComplexChannel(config.channel, config.preamble),
            peerDevices = listOf(UwbDevice.createForAddress(config.peerAddress)),
            updateRateType = rate.platformRate, // 1/2/3 == AUTOMATIC/INFREQUENT/FREQUENT
        )
        var activated = false
        session.prepareSession(parameters).collect { result ->
            when (result) {
                is RangingResult.RangingResultInitialized -> {
                    if (!activated) {
                        activated = true
                        record("UWB_LOCAL_OPENED", "Jetpack; encrypted peer OOB required")
                        // Initialized device is the local address supplied by AndroidX.
                        configureTag(config.payload(result.device.address.address, rate.intervalMillis))
                    }
                }
                is RangingResult.RangingResultPosition -> {
                    result.position.distance?.let {
                        emit(Measurement(it.value.toDouble(), android.os.SystemClock.elapsedRealtime()))
                    }
                }
                is RangingResult.RangingResultPeerDisconnected -> error("UWB_PEER_DISCONNECTED")
                else -> Unit
            }
        }
        error("UWB_FLOW_ENDED")
    }

    @RequiresApi(36)
    private fun platformRange(requestedRate: TrackingRate, configureTag: suspend (ByteArray) -> Unit): Flow<Measurement> = callbackFlow {
        val manager = checkNotNull(context.getSystemService(RangingManager::class.java)) { "RANGING_SERVICE_ABSENT" }
        val availability = connectionTimeout(5_000, "UWB_CAPABILITIES_TIMEOUT") {
            suspendCancellableCoroutine<RangingCapabilities> { continuation ->
                lateinit var callback: RangingManager.RangingCapabilitiesCallback
                callback = RangingManager.RangingCapabilitiesCallback { capabilities ->
                    manager.unregisterCapabilitiesCallback(callback)
                    if (continuation.isActive) continuation.resumeWith(Result.success(capabilities))
                }
                continuation.invokeOnCancellation { runCatching { manager.unregisterCapabilitiesCallback(callback) } }
                try { manager.registerCapabilitiesCallback(context.mainExecutor, callback) }
                catch (e: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
            }
        }
        record("PLATFORM_CAPABILITIES", "uwbAvailability=${availability.technologyAvailability[RangingManager.UWB]}")
        check(availability.technologyAvailability[RangingManager.UWB] == RangingCapabilities.ENABLED) { "UWB_DISABLED_OR_UNAVAILABLE" }
        val rate = effectiveRate(requestedRate, availability.uwbCapabilities?.supportedRangingUpdateRates.orEmpty())
        val config = SmartTagSessionConfig.create()
        val local = UwbAddress.createRandomShortAddress()
        val params = UwbRangingParams.Builder(config.sessionId, UwbRangingParams.CONFIG_UNICAST_DS_TWR,
            local, UwbAddress.fromBytes(config.peerAddress))
            .setComplexChannel(UwbComplexChannel.Builder().setChannel(config.channel).setPreambleIndex(config.preamble).build())
            .setSessionKeyInfo(config.sessionKey)
            .setSlotDuration(UwbRangingParams.DURATION_2_MS)
            .setRangingUpdateRate(rate.platformRate).build() // NORMAL == RawRangingDevice.UPDATE_RATE_NORMAL
        val rawDevice = RawRangingDevice.Builder().setRangingDevice(RangingDevice.Builder().build()).setUwbRangingParams(params).build()
        val raw = RawResponderRangingConfig.Builder().setRawRangingDevice(rawDevice).build()
        val preference = RangingPreference.Builder(RangingPreference.DEVICE_ROLE_RESPONDER, raw)
            .setSessionConfig(SessionConfig.Builder().setAngleOfArrivalNeeded(false)
                .setDataNotificationConfig(DataNotificationConfig.Builder()
                    .setNotificationConfigType(DataNotificationConfig.NOTIFICATION_CONFIG_ENABLE).build()).build()).build()
        val callback = object : RangingSession.Callback {
            override fun onOpened() {
                record("UWB_LOCAL_OPENED", "Platform RAW UWB-only; encrypted peer OOB required")
                launch {
                    try { configureTag(config.payload(local.addressBytes, rate.intervalMillis)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { close(e) }
                }
            }
            override fun onOpenFailed(reason: Int) { close(IllegalStateException("UWB_OPEN_FAILED_$reason")) }
            override fun onStarted(peer: RangingDevice, technology: Int) { record("UWB_STARTED", "technology=$technology") }
            override fun onResults(peer: RangingDevice, data: RangingData) {
                // Even with RAW UWB-only configuration, reject any non-UWB callback.
                if (data.rangingTechnology != RangingManager.UWB) { record("NON_UWB_REJECTED", "technology=${data.rangingTechnology}"); return }
                val distance = data.distance ?: return
                trySend(Measurement(distance.measurement, android.os.SystemClock.elapsedRealtime(), distance.confidence))
            }
            override fun onStopped(peer: RangingDevice, technology: Int) { close(IllegalStateException("UWB_STOPPED_$technology")) }
            override fun onClosed(reason: Int) { close(IllegalStateException("UWB_CLOSED_$reason")) }
        }
        val session = checkNotNull(manager.createRangingSession(context.mainExecutor, callback)) { "UWB_SESSION_REJECTED" }
        val cancellation = session.start(preference)
        awaitClose { cancellation.cancel() }
    }
}
