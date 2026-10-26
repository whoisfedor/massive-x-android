package com.massivex.android.wallet

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.*
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

/** Every file is in noBackupFilesDir, in addition to manifest/transfer exclusions. */
class AtomicCatalog(context: Context) : CatalogPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "wallet-catalog-v1.bin"))
    override fun read(): ByteArray? = storageErrors {
        try { file.openRead().use { input ->
            val bytes = input.readBytesLimited(262144)
            bytes
        } } catch (_: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) fail(WalletError.STORAGE) else null
        }
    }
    override fun write(bytes: ByteArray) = storageErrors {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val n = read(buffer); if (n < 0) break; requireWallet(out.size() + n <= limit, WalletError.CORRUPT); out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}

fun <T> storageErrors(block: () -> T): T = try { block() }
catch (e: WalletException) { throw e }
catch (_: UserNotAuthenticatedException) { fail(WalletError.AUTH) }
catch (_: KeyPermanentlyInvalidatedException) { fail(WalletError.INVALIDATED) }
catch (_: java.security.UnrecoverableKeyException) { fail(WalletError.INVALIDATED) }
catch (_: AEADBadTagException) { fail(WalletError.CORRUPT) }
catch (_: Exception) { fail(WalletError.STORAGE) }

class AndroidSecrets(private val context: Context, private val prefix: String = PREFIX) : SecretStorage {
    companion object {
        const val PREFIX = "massivex.seed.v1."
        const val AUTH_ALIAS = "massivex.action.v1"
        const val AUTH_SECONDS = 5
    }
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override fun checkCapability() {
        requireWallet(Build.VERSION.SDK_INT >= 30, WalletError.UNSUPPORTED)
        requireWallet(context.getSystemService(KeyguardManager::class.java).isDeviceSecure, WalletError.LOCK_REQUIRED)
    }
    private fun newKey(alias: String): SecretKey {
        if (Build.VERSION.SDK_INT < 30) fail(WalletError.UNSUPPORTED)
        checkCapability()
        requireWallet(!store().containsAlias(alias), WalletError.ORPHAN)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .build())
            generateKey()
        }
    }
    private fun key(alias: String): SecretKey = store().getKey(alias, null) as? SecretKey ?: fail(WalletError.MISSING_KEY)
    /** A fresh system prompt must precede this proof. The AES key enforces the OS auth window. */
    fun proveAuthorization() = storageErrors {
        checkCapability()
        val k = if (store().containsAlias(AUTH_ALIAS)) key(AUTH_ALIAS) else newKey(AUTH_ALIAS)
        Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, k); doFinal(ByteArray(32)).fill(0) }
    }
    override fun encrypt(wallet: Wallet, seed: ByteArray): Envelope = storageErrors {
        checkCapability(); requireWallet(seed.size == 32, WalletError.CORRUPT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val alias = prefix + wallet.id
        val created = newKey(alias)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, created) // Provider generates a fresh unpredictable IV.
            requireWallet(cipher.iv.size == 12, WalletError.STORAGE)
            cipher.updateAAD(wallet.aad())
            Envelope(1, cipher.iv, cipher.doFinal(seed))
        } catch (e: Exception) {
            // This draft has never returned an envelope and cannot have committed a record.
            // A failed cleanup remains detectable as an orphan; never delete an existing alias.
            try { store().deleteEntry(alias) } catch (_: Exception) { /* Preserve diagnostic evidence. */ }
            throw e
        }
    }
    override fun decrypt(wallet: Wallet): ByteArray = storageErrors {
        checkCapability()
        val envelope = wallet.envelope ?: fail(WalletError.WATCH_ONLY)
        requireWallet(envelope.version == 1, WalletError.VERSION)
        requireWallet(envelope.iv.size == 12 && envelope.ciphertext.size == 48, WalletError.CORRUPT)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(prefix + wallet.id), GCMParameterSpec(128, envelope.iv))
            updateAAD(wallet.aad()); doFinal(envelope.ciphertext)
        }
    }
    override fun delete(id: String) = storageErrors { store().deleteEntry(prefix + id) }
    override fun walletIds(): Set<String> = storageErrors { store().aliases().toList().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet() }
    fun keyInfo(id: String): KeyInfo = storageErrors {
        SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec(key(prefix + id), KeyInfo::class.java) as KeyInfo
    }
}
