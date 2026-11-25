package com.elect3.mobdev.personalfinance.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.elect3.mobdev.personalfinance.data.remote.*
import java.io.*
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, excluded from backup, authenticated encryption; no password is ever stored. */
internal class ProtectedSessionStore(context: Context, namespace: String, private val purpose: String = "session") : SessionStore {
    private val aad = (context.packageName + "|owli-session-v1|" + namespace + "|" + purpose).toByteArray(Charsets.UTF_8)
    private val suffix = MessageDigest.getInstance("SHA-256").digest(aad).joinToString("") { "%02x".format(it) }
    private val alias = "owli.session.v1.$suffix"
    private val file = AtomicFile(File(context.noBackupFilesDir, "owli-session-$suffix.bin"))
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Protected session key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized override fun read(): StoredSession? { synchronized(fileLock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.openRead().use { input ->
            require(input.channel.size() in 30..65536)
            input.readBytes()
        }
        val iv = bytes.copyOfRange(1,13)
        require(bytes[0].toInt() == 1)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv)); updateAAD(aad)
            doFinal(bytes, 13, bytes.size - 13)
        }
        try { return decode(plain) } finally { plain.fill(0); bytes.fill(0) }
    }
    }
    @Synchronized override fun write(value: StoredSession) { synchronized(fileLock) {
        val plain = encode(value)
        val encrypted = try { Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key(true)); updateAAD(aad)
            byteArrayOf(1) + iv + doFinal(plain)
        } } finally { plain.fill(0) }
        var output: FileOutputStream? = null
        try { output = file.startWrite(); output.write(encrypted); file.finishWrite(output) }
        catch (failure: Exception) { output?.let { file.failWrite(it) }; throw failure }
        finally { encrypted.fill(0) }
    }
    }
    @Synchronized override fun clear() { synchronized(fileLock) {
        // Deleting the key first makes even a leftover ciphertext/AtomicFile backup unrecoverable.
        keyStore.deleteEntry(alias)
        file.delete()
    }
    }
    @Synchronized override fun writeIfOwned(expected: AuthPayload, value: StoredSession): Boolean = synchronized(fileLock) {
        if (read()?.payload?.refreshToken != expected.refreshToken) false else { write(value); true }
    }
    @Synchronized override fun clearIfOwned(expected: AuthPayload) = synchronized(fileLock) {
        if (read()?.payload?.refreshToken == expected.refreshToken) clear()
    }
    private companion object { val fileLock = Any() }
    private fun encode(value: StoredSession): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            val p = value.payload; data.writeInt(1); data.writeBoolean(value.renewalUncertain)
            data.writeUTF(p.accessToken); data.writeUTF(p.refreshToken)
            data.writeLong(p.accessExpires.epochSecond); data.writeLong(p.refreshExpires.epochSecond)
            data.writeInt(p.user.id); data.writeUTF(p.user.fullName); data.writeUTF(p.user.email); data.writeUTF(p.user.baseCurrency)
            data.writeInt(p.user.roles.size); p.user.roles.forEach(data::writeUTF)
        }; output.toByteArray().also { require(it.size <= 60000) }
    }
    private fun decode(bytes: ByteArray): StoredSession = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        require(data.readInt() == 1)
        val pending = data.readBoolean(); val access = data.readUTF(); val refresh = data.readUTF()
        val accessExpiry = Instant.ofEpochSecond(data.readLong()); val refreshExpiry = Instant.ofEpochSecond(data.readLong())
        val id = data.readInt(); val name = data.readUTF(); val email = data.readUTF(); val currency = data.readUTF()
        val count = data.readInt(); require(count in 1..32)
        val roles = List(count) { data.readUTF().also { require(it.length in 1..100) } }
        require(data.available() == 0 && access.length in 1..16384 && access.all { it.code in 33..126 })
        require(refresh.matches(Regex("[A-Za-z0-9_-]{86}")) && id > 0 && name.length in 1..100 && email.length in 1..256)
        require(currency.matches(Regex("[A-Z]{3}")) && !refreshExpiry.isBefore(accessExpiry))
        StoredSession(AuthPayload(access, refresh, accessExpiry, refreshExpiry, AuthUser(id,name,email,currency,roles)),pending)
    }
}

