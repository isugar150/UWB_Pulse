package dev.uwbpulse.monitor.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.UUID

/** Owner-authenticated SmartTag transport. Only nonce exchange and UWB control are written.
 * Wire protocol: USENIX Security 2024 (Yu et al.), verified against the installed app.
 * No account/nonce/key/ciphertext or peer address is logged.
 */
@SuppressLint("MissingPermission")
class GattTransport(private val context: Context, private val record: (String, String) -> Unit) {
    private var gatt: BluetoothGatt? = null
    private val ready = CompletableDeferred<Unit>()
    private val serial = Mutex()
    private val commands = Mutex()
    private data class Pending(val kind: String, val uuid: UUID?, val result: CompletableDeferred<ByteArray>)
    @Volatile private var pending: Pending? = null
    private val tagNonces = Channel<ByteArray>(8)
    private val tagProofs = Channel<ByteArray>(8)
    private var crypto: SmartTagCrypto? = null
    private var activated = false
    val disconnected = CompletableDeferred<Int>()
    private fun finish(kind: String, uuid: UUID?, status: Int, value: ByteArray = byteArrayOf()) {
        val operation = pending ?: return
        if (operation.kind != kind || operation.uuid != uuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) operation.result.complete(value)
        else operation.result.completeExceptionally(IllegalStateException("GATT_${kind.uppercase()}_STATUS_$status"))
    }
    private fun indication(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (bytes.size > 256) return
        when (characteristic.uuid) {
            NONCE -> tagNonces.trySend(bytes.copyOf())
            ENCRYPTED_NONCE -> tagProofs.trySend(bytes.copyOf())
        }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                val error = IllegalStateException("GATT_CONNECTION_STATUS_$status")
                ready.completeExceptionally(error); pending?.result?.completeExceptionally(error)
                tagNonces.close(error); tagProofs.close(error); disconnected.complete(status)
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                record("GATT_CONNECTED", "discovering auth and UWB services")
                if (!client.discoverServices()) ready.completeExceptionally(IllegalStateException("GATT_DISCOVERY_REJECTED"))
            }
        }
        override fun onServicesDiscovered(client: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { ready.completeExceptionally(IllegalStateException("GATT_DISCOVERY_STATUS_$status")); return }
            if (client.getService(AUTH_SERVICE)?.getCharacteristic(NONCE) == null || client.getService(AUTH_SERVICE)?.getCharacteristic(ENCRYPTED_NONCE) == null) {
                ready.completeExceptionally(IllegalStateException("TAG_AUTH_SERVICE_ABSENT")); return
            }
            if (client.getService(BleDiscovery.SERVICE)?.getCharacteristic(ACTIVATION) == null || client.getService(BleDiscovery.SERVICE)?.getCharacteristic(PARAMETERS) == null) {
                ready.completeExceptionally(IllegalStateException("UWB_CHARACTERISTICS_ABSENT")); return
            }
            record("UWB_CHARACTERISTICS_FOUND", "owner authentication service present")
            ready.complete(Unit)
        }
        override fun onMtuChanged(client: BluetoothGatt, mtu: Int, status: Int) { finish("mtu",null,status,ByteBuffer.allocate(4).putInt(mtu).array()) }
        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { finish("descriptor",descriptor.characteristic.uuid,status) }
        override fun onCharacteristicWrite(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { finish("write",characteristic.uuid,status) }
        @Deprecated("Compatibility callback for API 31/32")
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            indication(characteristic,value)
        }
        override fun onCharacteristicChanged(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { indication(characteristic,value) }
    }
    suspend fun connect(address: String) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        check(adapter.isEnabled) { "BLUETOOTH_DISABLED" }
        @Suppress("DEPRECATION")
        val connection = adapter.getRemoteDevice(address).connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE)
        gatt = checkNotNull(connection) { "GATT_CONNECT_REJECTED" }
        try { withTimeout(15_000) { ready.await() } }
        catch (_: TimeoutCancellationException) { error("GATT_CONNECT_OR_DISCOVERY_TIMEOUT") }
    }
    suspend fun authenticate(masterSecret: ByteArray) {
        check(crypto == null) { "TAG_AUTH_ALREADY_STARTED" }
        val session = SmartTagCrypto(masterSecret)
        crypto = session
        val mtu = ByteBuffer.wrap(operation("mtu",null) { checkNotNull(gatt).requestMtu(256) }).int
        check(mtu >= 51) { "GATT_MTU_TOO_SMALL_FOR_ENCRYPTED_UWB" }
        record("GATT_MTU_READY","mtu=$mtu")
        enableIndications(AUTH_SERVICE,NONCE)
        enableIndications(AUTH_SERVICE,ENCRYPTED_NONCE)
        record("TAG_AUTH_START","owner mutual challenge-response")
        write(AUTH_SERVICE,NONCE,session.localNonce)
        val nonce = try { withTimeout(8_000) { tagNonces.receive() } }
            catch (_: TimeoutCancellationException) { error("TAG_NONCE_TIMEOUT") }
        val proof = try { session.phoneProof(nonce) } finally { nonce.fill(0) }
        try { write(AUTH_SERVICE,ENCRYPTED_NONCE,proof) } finally { proof.fill(0) }
        val response = try { withTimeout(8_000) { tagProofs.receive() } }
            catch (_: TimeoutCancellationException) { error("TAG_PROOF_TIMEOUT") }
        try { session.verifyTagProof(response) } finally { response.fill(0) }
        record("TAG_AUTHENTICATED","mutual owner proof verified; encrypted command channel ready")
    }
    suspend fun activate(payload: ByteArray) = commands.withLock {
        check(payload.size == 35) { "UWB_PAYLOAD_SIZE_INVALID" }
        activated = true
        encryptedWrite(ACTIVATION,byteArrayOf(1))
        encryptedWrite(PARAMETERS,payload)
        record("OOB_ENCRYPTED_ACKNOWLEDGED","UWB activation and session parameters accepted by ATT; awaiting actual ranging reports")
    }
    suspend fun deactivate() {
        if (!activated || disconnected.isCompleted) return
        commands.withLock {
            withTimeout(2_500) { encryptedWrite(ACTIVATION,byteArrayOf(0)) }
            activated = false
        }
        record("TAG_UWB_STOPPED","encrypted stop acknowledged")
    }
    private suspend fun encryptedWrite(uuid: UUID, plain: ByteArray) {
        val session = checkNotNull(crypto) { "TAG_NOT_AUTHENTICATED" }
        val ciphertext = session.encryptCommand(plain)
        try { write(BleDiscovery.SERVICE,uuid,ciphertext); session.commandAcknowledged() }
        finally { ciphertext.fill(0) }
    }
    private suspend fun enableIndications(service: UUID, uuid: UUID) {
        val client = checkNotNull(gatt)
        val characteristic = characteristic(service,uuid)
        check(characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) { "TAG_AUTH_INDICATION_UNSUPPORTED" }
        check(client.setCharacteristicNotification(characteristic,true)) { "TAG_INDICATION_ENABLE_REJECTED" }
        val descriptor = checkNotNull(characteristic.getDescriptor(CCCD)) { "TAG_AUTH_CCCD_ABSENT" }
        operation("descriptor",uuid) {
            if (Build.VERSION.SDK_INT >= 33) client.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            else writeDescriptorLegacy(client,descriptor,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        }
    }
    private fun characteristic(service: UUID, uuid: UUID) = checkNotNull(gatt?.getService(service)?.getCharacteristic(uuid)) { "TAG_CHARACTERISTIC_ABSENT" }
    private suspend fun write(service: UUID, uuid: UUID, bytes: ByteArray) {
        val client = checkNotNull(gatt)
        val characteristic = characteristic(service,uuid)
        check(characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) { "TAG_WRITE_WITH_RESPONSE_UNSUPPORTED" }
        operation("write",uuid) {
            if (Build.VERSION.SDK_INT >= 33) {
                val result = client.writeCharacteristic(characteristic,bytes,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) error("GATT_WRITE_STATUS_132")
                result == BluetoothStatusCodes.SUCCESS
            } else writeCharacteristicLegacy(client,characteristic,bytes)
        }
    }
    // API 31/32 only: the value-less overloads are deprecated from API 33.
    @Suppress("DEPRECATION")
    private fun writeDescriptorLegacy(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        descriptor.value = value
        return client.writeDescriptor(descriptor)
    }
    @Suppress("DEPRECATION")
    private fun writeCharacteristicLegacy(client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        characteristic.value = bytes
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return client.writeCharacteristic(characteristic)
    }
    private suspend fun operation(kind: String, uuid: UUID?, enqueue: () -> Boolean): ByteArray = serial.withLock {
        for (attempt in 0..3) {
            val completion = CompletableDeferred<ByteArray>()
            pending = Pending(kind,uuid,completion)
            try {
                check(enqueue()) { "GATT_OPERATION_NOT_QUEUED" }
                return@withLock withTimeout(5_000) { completion.await() }
            } catch (_: TimeoutCancellationException) {
                close() // prevent a late callback from completing a subsequent request
                error("GATT_OPERATION_TIMEOUT")
            } catch (e: IllegalStateException) {
                if (e.message == "GATT_${kind.uppercase()}_STATUS_132" && attempt < 3) {
                    record("GATT_BUSY_RETRY","operation=$kind; attempt=${attempt + 1}")
                } else throw e
            } finally { pending = null }
            delay(200L shl attempt)
        }
        error("GATT_BUSY_RETRIES_EXHAUSTED")
    }
    fun close() {
        runCatching { gatt?.disconnect() }; runCatching { gatt?.close() }; gatt = null
        crypto?.close(); crypto = null
        tagNonces.close(); tagProofs.close()
    }
    companion object {
        private val AUTH_SERVICE = UUID.fromString("eedd5e73-6aa8-4673-8219-398a489da87c")
        private val NONCE = UUID.fromString("a12be31c-5b38-4773-9b9d-3d5735233a7c")
        private val ENCRYPTED_NONCE = UUID.fromString("4ebe81f6-b952-465e-9ece-5ca39d4e8955")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val ACTIVATION = UUID.fromString("dee30008-182d-5496-b1ad-14f216324184")
        private val PARAMETERS = UUID.fromString("dee30009-182d-5496-b1ad-14f216324184")
    }
}
