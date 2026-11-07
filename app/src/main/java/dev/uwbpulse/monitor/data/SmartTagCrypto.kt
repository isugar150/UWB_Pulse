package dev.uwbpulse.monitor.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Owner protocol from Yu et al., USENIX Security 2024, sections 3.1/3.2.
 * Independently implemented; keys must come from the authenticated owner's device record.
 */
class SmartTagCrypto(masterSecret: ByteArray, val localNonce: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)) : AutoCloseable {
    private val master = masterSecret.copyOf().also { require(it.size == 16) { "TAG_KEY_LENGTH_UNSUPPORTED" } }
    private val authKey = derive(master, "bleAuthentication".toByteArray(Charsets.UTF_8))
    private var remoteNonce: ByteArray? = null
    private var commandKey: ByteArray? = null
    private var authenticated = false
    private var proofConsumed = false
    private var counter = 1L
    init { require(localNonce.size == 16) }
    fun phoneProof(nonce: ByteArray): ByteArray {
        require(nonce.size == 16) { "TAG_NONCE_INVALID" }
        check(!MessageDigest.isEqual(localNonce,nonce)) { "AUTH_REFLECTION_REJECTED" }
        check(remoteNonce == null) { "AUTH_NONCE_ALREADY_SET" }
        remoteNonce = nonce.copyOf()
        commandKey = derive(master,nonce)
        master.fill(0)
        return aes(Cipher.ENCRYPT_MODE,authKey,nonce,MAGIC)
    }
    fun verifyTagProof(proof: ByteArray) {
        check(!proofConsumed) { "TAG_AUTH_PROOF_ALREADY_CONSUMED" }
        proofConsumed = true
        check(remoteNonce != null) { "AUTH_NONCE_MISSING" }
        check(proof.size == 16) { "TAG_AUTH_PROOF_INVALID" }
        val clear = try { aes(Cipher.DECRYPT_MODE,authKey,localNonce,proof) }
            catch (_: Exception) { error("TAG_AUTHENTICATION_FAILED") }
        try { check(MessageDigest.isEqual(clear,MAGIC)) { "TAG_AUTHENTICATION_FAILED" }; authenticated = true }
        finally { clear.fill(0); authKey.fill(0) }
    }
    fun encryptCommand(command: ByteArray): ByteArray {
        check(authenticated) { "TAG_NOT_AUTHENTICATED" }
        check(command.isNotEmpty() && counter <= 0xffffffffL) { "TAG_COMMAND_COUNTER_EXHAUSTED" }
        val data = ByteBuffer.allocate(4 + command.size).order(ByteOrder.LITTLE_ENDIAN).putInt(counter.toInt()).put(command).array()
        return try { aes(Cipher.ENCRYPT_MODE,checkNotNull(commandKey),checkNotNull(remoteNonce),data) } finally { data.fill(0) }
    }
    /** Advance only after ATT success; transient busy retry sends the identical ciphertext. */
    fun commandAcknowledged() { check(authenticated); counter++ }
    override fun close() {
        master.fill(0); authKey.fill(0); localNonce.fill(0); remoteNonce?.fill(0); commandKey?.fill(0); authenticated = false
    }
    companion object {
        private val MAGIC = "smartthings".toByteArray(Charsets.UTF_8)
        fun ownerMasterSecret(cloudSecret: ByteArray): ByteArray {
            require(cloudSecret.size == 16 || cloudSecret.size == 32) { "TAG_KEY_LENGTH_UNSUPPORTED" }
            return cloudSecret.copyOf(16)
        }
        fun derive(secret: ByteArray, label: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(secret + byteArrayOf(0,0,0,1) + label).copyOf(16)
        fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(mode,SecretKeySpec(key,"AES"),IvParameterSpec(iv)); doFinal(data)
        }
        fun matchesAdvertisement(master: ByteArray, iv: ByteArray, advertised: ByteArray): Boolean {
            if (master.size != 16 || iv.size != 16 || advertised.size != 20) return false
            val key = derive(master,"signing".toByteArray(Charsets.UTF_8))
            return try { MessageDigest.isEqual(aes(Cipher.ENCRYPT_MODE,key,iv,advertised.copyOfRange(0,16)).copyOf(4),advertised.copyOfRange(16,20)) }
            finally { key.fill(0) }
        }
    }
}
