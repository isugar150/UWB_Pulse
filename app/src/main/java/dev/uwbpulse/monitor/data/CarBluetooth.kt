package dev.uwbpulse.monitor.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import dev.uwbpulse.monitor.domain.CarLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** A bonded device the user may pick as the car. The address stays on the device; it is never logged. */
data class BondedDevice(val name: String, val address: String)

/**
 * Watches one bonded device (the car head unit). Reads only connection state of that device; nothing is written to it.
 * Names and addresses are never logged: the journal gets `CAR_LINK` with the source and the new state only.
 */
@SuppressLint("MissingPermission")
class CarBluetooth(private val context: Context, private val record: (String, String) -> Unit) {
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    fun bonded(): List<BondedDevice> = runCatching {
        adapter?.bondedDevices.orEmpty().map { BondedDevice(it.name ?: "이름 없음", it.address) }.sortedBy { it.name }
    }.getOrDefault(emptyList())

    /** Is the car connected right now? Null when the profile services did not answer in time. */
    suspend fun connectedNow(address: String, link: CarLink): Boolean? {
        val a2dp = profileHas(BluetoothProfile.A2DP, address) ?: return null
        val headset = profileHas(BluetoothProfile.HEADSET, address) ?: return null
        return link.seed(a2dp, headset)
    }

    private suspend fun profileHas(profile: Int, address: String): Boolean? {
        val bluetooth = adapter ?: return null
        if (!bluetooth.isEnabled) return false
        val result = CompletableDeferred<Boolean>()
        var proxy: BluetoothProfile? = null
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(kind: Int, service: BluetoothProfile) {
                proxy = service
                result.complete(runCatching { service.connectedDevices.any { it.address.equals(address, ignoreCase = true) } }.getOrDefault(false))
            }
            override fun onServiceDisconnected(kind: Int) { result.complete(false) }
        }
        if (!bluetooth.getProfileProxy(context, listener, profile)) return null
        return try { withTimeoutOrNull(3_000) { result.await() } }
        finally { proxy?.let { runCatching { bluetooth.closeProfileProxy(profile, it) } } }
    }

    /**
     * Registers for ACL and profile changes of [address]; [changed] receives the new combined state only when it changes.
     * Returns the function that unregisters. Exported on purpose: see the comment at the registration.
     */
    fun observe(address: String, link: CarLink, changed: (Boolean) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val device = deviceOf(intent)
                if (device == null) { record("CAR_LINK_OTHER","action=${shortAction(intent.action)}; device=none"); return }
                if (!device.address.equals(address, ignoreCase = true)) {
                    // Names and addresses are never logged: this only shows that other devices' events do arrive.
                    record("CAR_LINK_OTHER","action=${shortAction(intent.action)}; state=${intent.getIntExtra(BluetoothProfile.EXTRA_STATE,-1)}; transport=${intent.getIntExtra("android.bluetooth.device.extra.TRANSPORT",-1)}")
                    return
                }
                val (source, result) = when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> if (isLowEnergy(intent)) return else "acl" to link.acl(true)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (isLowEnergy(intent)) return else "acl" to link.acl(false)
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> "a2dp" to profileState(intent)?.let { link.profile(CarLink.Profile.A2DP, it) }
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> "hfp" to profileState(intent)?.let { link.profile(CarLink.Profile.HEADSET, it) }
                    else -> return
                }
                result?.let { record("CAR_LINK", "connected=$it; via=$source"); changed(it) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        // The Bluetooth stack sends these as uid 1002 (com.android.bluetooth), which is neither root nor the system uid, so a
        // NOT_EXPORTED receiver would silently never get them (found on the device with earbuds standing in for the car).
        // All four actions are protected broadcasts: only the system can send them, so an exported receiver cannot be spoofed.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun shortAction(action: String?) = when (action) {
        BluetoothDevice.ACTION_ACL_CONNECTED -> "acl_connected"
        BluetoothDevice.ACTION_ACL_DISCONNECTED -> "acl_disconnected"
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> "a2dp"
        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> "hfp"
        else -> "other"
    }
    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    /** A car may also hold a Low Energy link of its own (digital key); only the classic link means "the car is here". */
    private fun isLowEnergy(intent: Intent) = intent.getIntExtra("android.bluetooth.device.extra.TRANSPORT", TRANSPORT_AUTO) == TRANSPORT_LE

    /** Only the two settled states count; connecting and disconnecting are ignored. */
    private fun profileState(intent: Intent): Boolean? = when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
        BluetoothProfile.STATE_CONNECTED -> true
        BluetoothProfile.STATE_DISCONNECTED -> false
        else -> null
    }

    private companion object {
        const val TRANSPORT_AUTO = 0
        const val TRANSPORT_LE = 2
    }
}
