package com.massivex.android.transfer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted public-operation journal. Separate key; seed key authentication policy is unchanged. */
internal class AndroidTransferJournal(private val context: Context, private val namespace: String = "mainnet-v1") : JournalPersistence {
    init { demand(namespace.matches(Regex("[a-zA-Z0-9-]{1,80}")),SendError.JOURNAL) }
    companion object { private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>() }
    private val lock=locks.computeIfAbsent(File(context.noBackupFilesDir,"transfers-$namespace.bin").path){Any()}
    val alias = "massivex.transfer-journal.$namespace"
    val path = File(context.noBackupFilesDir,"transfers-$namespace.bin")
    private val file = AtomicFile(path)
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key() = store().getKey(alias,null) as? SecretKey ?: throw SendFailure(SendError.JOURNAL)
    private fun aad(id: String) = "MassiveX/transfer-journal/v1\u0000${context.packageName}\u0000$namespace\u0000$id".toByteArray()
    @Synchronized override fun read(): JournalFile = guarded {
        val encrypted = try { readRaw() } catch (_: FileNotFoundException) {
            // Key is also the existence anchor. Loss after initialization must never become an empty journal.
            demand(!store().containsAlias(alias) && !path.exists() && !File(path.path+".new").exists() && !File(path.path+".bak").exists(),SendError.JOURNAL)
            val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
            generator.generateKey() // Crash before initial file: surviving anchor fails closed on next read.
            val initial=JournalFile(UUID.randomUUID().toString()); writeEncrypted(initial); return@guarded initial
        }
        demand(store().containsAlias(alias),SendError.JOURNAL)
        DataInputStream(ByteArrayInputStream(encrypted)).use { d ->
            demand(d.readInt()==0x4d584145 && d.readInt()==1,SendError.JOURNAL)
            val id=d.readUTF(); demand(id.length==36 && UUID.fromString(id).toString()==id,SendError.JOURNAL)
            val iv=ByteArray(12);d.readFully(iv);val size=d.readInt();demand(size in 16..JournalCodec.MAX_BYTES+16 && d.available()==size,SendError.JOURNAL)
            val ciphertext=ByteArray(size);d.readFully(ciphertext)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv));cipher.updateAAD(aad(id))
            val plain=cipher.doFinal(ciphertext)
            try { JournalCodec.decode(plain).also { demand(it.installationId==id,SendError.JOURNAL) } } finally { plain.fill(0) }
        }
    }
    @Synchronized override fun write(file: JournalFile) = guarded {
        val existing=read();demand(existing.installationId==file.installationId && file.revision==existing.revision+1,SendError.JOURNAL)
        writeEncrypted(file)
    }
    private fun writeEncrypted(value: JournalFile) {
        val plain=JournalCodec.encode(value)
        val bytes=try {
            val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(aad(value.installationId))
            demand(c.iv.size==12,SendError.JOURNAL)
            val ciphertext=c.doFinal(plain)
            ByteArrayOutputStream().also { out -> DataOutputStream(out).use { d ->
                d.writeInt(0x4d584145);d.writeInt(1);d.writeUTF(value.installationId);d.write(c.iv);d.writeInt(ciphertext.size);d.write(ciphertext)
            } }.toByteArray()
        } finally { plain.fill(0) }
        val stream=file.startWrite()
        try { stream.write(bytes);stream.fd.sync();file.finishWrite(stream) }
        catch(e:Exception) { file.failWrite(stream);throw e }
        val descriptor=Os.open(path.parentFile!!.path,OsConstants.O_RDONLY,0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        demand(readRaw().contentEquals(bytes),SendError.JOURNAL)
    }
    private fun readRaw():ByteArray = file.openRead().use { input ->
        val out=ByteArrayOutputStream();val chunk=ByteArray(8192)
        while(true){val n=input.read(chunk);if(n<0)break;demand(out.size()+n<=JournalCodec.MAX_BYTES+256,SendError.JOURNAL);out.write(chunk,0,n)}
        out.toByteArray()
    }
    private inline fun <T> guarded(block:()->T):T = synchronized(lock) { try { block() } catch(e:SendFailure){throw e} catch(_:Exception){throw SendFailure(SendError.JOURNAL)} }
}

/** One shared process ledger/gate across Activity instances and configuration changes. */
internal object ProcessTransferStore {
    private val stores=java.util.concurrent.ConcurrentHashMap<String,TransferLedger>()
    fun ledger(context:Context):TransferLedger = stores.computeIfAbsent(context.noBackupFilesDir.path){TransferLedger(AndroidTransferJournal(context.applicationContext))}
}
