package dev.uwbpulse.monitor.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import dev.uwbpulse.monitor.domain.TagCandidate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/** FD5A is a SmartTag family service, not proof of model, ownership or UWB support. */
@SuppressLint("MissingPermission")
class BleDiscovery(private val context: Context, private val record: (String, String) -> Unit,
    private val matchOwner: (ByteArray) -> String? = { null }) {
    fun scan() = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        check(adapter?.isEnabled == true) { "BLUETOOTH_DISABLED" }
        val scanner = checkNotNull(adapter.bluetoothLeScanner) { "BLE_SCANNER_UNAVAILABLE" }
        val candidates = linkedMapOf<String, TagCandidate>()
        val owners = HashMap<String, Pair<ByteArray, String?>>() // advertisement bytes -> owner, so a repeated advertisement is verified once
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                // Never interpret RSSI as distance; do not even retain it.
                val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE))
                // uTag decodeServiceData: low nibble of byte 12, bit 2. Not a model/owner proof.
                val uwb = serviceData?.takeIf { it.size >= 13 }?.let { (it[12].toInt() and 4) != 0 }
                val ownerId = serviceData?.let { data ->
                    val cached = owners[address]?.takeIf { it.first.contentEquals(data) }
                    if (cached != null) cached.second else matchOwner(data).also { owners[address] = data.copyOf() to it }
                }
                val candidate = TagCandidate(address, "SmartTag 계열 후보 ${candidates.keys.indexOf(address).takeIf { it >= 0 }?.plus(1) ?: (candidates.size + 1)}", result.isConnectable, uwb,ownerId)
                if (candidates[address] != candidate) record("BLE_CANDIDATE", "connectable=${candidate.connectable}; advertisedUwb=$uwb; serviceDataBytes=${serviceData?.size ?: 0}; ownerSignatureMatched=${ownerId != null}")
                candidates[address] = candidate
                trySend(candidates.values.toList())
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }
            override fun onScanFailed(errorCode: Int) { close(IllegalStateException("BLE_SCAN_FAILED_$errorCode")) }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        try {
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            awaitClose { runCatching { scanner.stopScan(callback) } }
        } catch (e: Exception) {
            runCatching { scanner.stopScan(callback) }
            throw e
        }
    }
    companion object { val SERVICE: UUID = UUID.fromString("0000fd5a-0000-1000-8000-00805f9b34fb") }
}
