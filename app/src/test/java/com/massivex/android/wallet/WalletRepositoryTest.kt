package com.massivex.android.wallet

import com.massivex.massa.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** PUBLIC SYNTHETIC SEEDS ONLY. Fakes are confined to JVM tests, never production. */
class WalletRepositoryTest {
    class Disk : CatalogPersistence {
        var bytes: ByteArray? = null; var broken = false
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) { if (broken) fail(WalletError.STORAGE); this.bytes = bytes.copyOf() }
    }
    class Auth : OwnerAuthentication {
        var time = 0L; var epoch = 0; var denied: WalletError? = null; var requests = 0
        var intercept: (suspend () -> Unit)? = null
        override suspend fun approve(action: Action): Approval {
            requests++; denied?.let { fail(it) }; intercept?.invoke()
            val generation = epoch
            return Approval(action, Clock { time }, time) { epoch == generation }
        }
        override fun cancel() { epoch++ }
    }
    class Secrets : SecretStorage {
        val keys = mutableMapOf<String, ByteArray>(); var capability: WalletError? = null; var decryptFailure: WalletError? = null; var deleteFailure = false
        override fun checkCapability() { capability?.let { fail(it) } }
        override fun walletIds() = keys.keys.toSet()
        override fun encrypt(wallet: Wallet, seed: ByteArray): Envelope {
            val k = ByteArray(32) { 77 }; keys[wallet.id] = k
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES")); c.updateAAD(wallet.aad())
            return Envelope(1, c.iv, c.doFinal(seed))
        }
        override fun decrypt(wallet: Wallet): ByteArray {
            decryptFailure?.let { fail(it) }
            val k = keys[wallet.id] ?: fail(WalletError.MISSING_KEY)
            val e = wallet.envelope!!
            try { return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, e.iv)); updateAAD(wallet.aad()); doFinal(e.ciphertext) } }
            catch (_: Exception) { fail(WalletError.CORRUPT) }
        }
        override fun delete(id: String) { if (deleteFailure) fail(WalletError.STORAGE); keys.remove(id) }
    }
    private val disk = Disk(); private val secrets = Secrets(); private val auth = Auth()
    private var seedIndex = 0
    private fun repo() = WalletRepository(disk, secrets, Randomness { ByteArray(32) { seedIndex.toByte() }.also { seedIndex++ } }, auth)
    private val repository = repo()
    private fun public(seed: Int = 0) = OfflineAccount.fromSeed(ByteArray(32) { seed.toByte() }).use { it.publicAccount() }
    private fun key(seed: Int = 0) = OfflineAccount.fromSeed(ByteArray(32) { seed.toByte() }).use { it.privateKeyEncoding() }
    private fun expect(error: WalletError, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("Expected safe failure") } catch (e: WalletException) { assertEquals(error, e.reason) }
    }
    @Test fun createPersistsParityAndReopens() = runBlocking { val c = repository.create(""); assertEquals(public().address, c.selected!!.address); assertEquals(c.selected!!.address, repo().load().selected!!.address) }
    @Test fun reinstantiateDecryptsAfterNewAuth() = runBlocking { val w = repository.create("").selected!!; var same = false; repo().backup(w.id, Purpose.REVEAL) { same = it == key() }; assertTrue(same); assertEquals(2, auth.requests) }
    @Test fun privateImportCanonical() = runBlocking { assertEquals(public().address, repository.importPrivate(key(), "Imported").selected!!.address) }
    @Test fun invalidKeyCreatesNothing() { expect(WalletError.INVALID_KEY) { repository.importPrivate("Sbad", "") }; assertNull(disk.bytes); assertTrue(secrets.keys.isEmpty()) }
    @Test fun wrongChecksumCreatesNothing() { expect(WalletError.INVALID_KEY) { repository.importPrivate(key().dropLast(1) + "X", "") }; assertNull(disk.bytes) }
    @Test fun watchNeverUsesAuthOrStorage() { val c = repository.addWatch(public().address, ""); assertTrue(c.selected!!.watchOnly); assertTrue(secrets.keys.isEmpty()); assertEquals(0, auth.requests) }
    @Test fun watchBackupRefused() { val w = repository.addWatch(public().address, "").selected!!; expect(WalletError.WATCH_ONLY) { repository.backup(w.id, Purpose.REVEAL) { fail("No export") } }; assertEquals(0, auth.requests) }
    @Test fun invalidWatchRefused() { expect(WalletError.INVALID_ADDRESS) { repository.addWatch("AUbad", "") }; assertNull(disk.bytes) }
    @Test fun watchAllowedWithoutSecureLock() { secrets.capability = WalletError.LOCK_REQUIRED; assertTrue(repository.addWatch(public().address, "").selected!!.watchOnly) }
    @Test fun createRequiresLock() { secrets.capability = WalletError.LOCK_REQUIRED; expect(WalletError.LOCK_REQUIRED) { repository.create("") }; assertNull(disk.bytes) }
    @Test fun oldApisRefuseSecrets() { secrets.capability = WalletError.UNSUPPORTED; expect(WalletError.UNSUPPORTED) { repository.importPrivate(key(), "") }; assertNull(disk.bytes) }
    @Test fun defaultNamingCollisions() = runBlocking { repository.create(""); repository.create(""); val c = repository.create(""); assertEquals(listOf("Wallet", "Wallet 2", "Wallet 3"), c.wallets.map { it.name }) }
    @Test fun selectAndRenamePreserveDecryption() = runBlocking { val a = repository.create("").selected!!; repository.create(""); repository.select(a.id); repository.rename(a.id, " My name "); repository.backup(a.id, Purpose.REVEAL) { assertTrue(it == key()) }; assertEquals("My name", repo().load().selected!!.name) }
    @Test fun duplicateIdentityRefused() = runBlocking { repository.create(""); expect(WalletError.DUPLICATE) { repository.importPrivate(key(), "other") }; assertEquals(1, repository.load().wallets.size) }
    @Test fun deleteExactlySelected() = runBlocking { val a = repository.create("").selected!!; val b = repository.create("").selected!!; val c = repository.remove(b.id, true); assertEquals(listOf(a.id), c.wallets.map { it.id }); assertEquals(setOf(a.id), secrets.walletIds()) }
    @Test fun deleteNonselectedRefused() = runBlocking { val a = repository.create("").selected!!; repository.create(""); expect(WalletError.CHANGED) { repository.remove(a.id, true) }; assertEquals(2, repository.load().wallets.size) }
    @Test fun deletionRequiresAcknowledgement() = runBlocking { val w = repository.create("").selected!!; expect(WalletError.CHANGED) { repository.remove(w.id, false) }; assertEquals(1, repository.load().wallets.size) }
    @Test fun watchRemovalNeedsOnlyConfirmation() = runBlocking { val w = repository.addWatch(public().address, "").selected!!; assertTrue(repository.remove(w.id, true).wallets.isEmpty()); assertEquals(0, auth.requests) }
    @Test fun cancelledCreateLeavesNoRecordOrAlias() { auth.denied = WalletError.CANCELLED; expect(WalletError.CANCELLED) { repository.create("") }; assertNull(disk.bytes); assertTrue(secrets.keys.isEmpty()) }
    @Test fun deniedBackupDoesNotExport() = runBlocking { val w = repository.create("").selected!!; auth.denied = WalletError.AUTH; expect(WalletError.AUTH) { repository.backup(w.id, Purpose.COPY) { fail("No export") } } }
    @Test fun timeoutDoesNotDelete() = runBlocking { val w = repository.create("").selected!!; auth.denied = WalletError.EXPIRED; expect(WalletError.EXPIRED) { repository.remove(w.id, true) }; assertEquals(1, repository.load().wallets.size) }
    @Test fun grantIsOneUse() { val a = Action("id", Purpose.ADD); val p = Approval(a, Clock { 0 }, 0) { true }; p.consume(a); expect(WalletError.CHANGED) { p.consume(a) } }
    @Test fun grantBoundToAction() { val p = Approval(Action("id", Purpose.REVEAL), Clock { 0 }, 0) { true }; expect(WalletError.CHANGED) { p.consume(Action("id", Purpose.COPY)) } }
    @Test fun grantBoundToWallet() { val p = Approval(Action("one", Purpose.REMOVE), Clock { 0 }, 0) { true }; expect(WalletError.CHANGED) { p.consume(Action("two", Purpose.REMOVE)) } }
    @Test fun grantExpires() { val a = Action("id", Purpose.ADD); val p = Approval(a, Clock { 4001 }, 0) { true }; expect(WalletError.EXPIRED) { p.consume(a) } }
    @Test fun backgroundRevokesGrant() { val a = Action("id", Purpose.ADD); val p = Approval(a, Clock { 0 }, 0) { false }; expect(WalletError.CHANGED) { p.consume(a) } }
    @Test fun noPlainSeedInCatalog() = runBlocking { repository.create(""); val b = disk.bytes!!; assertFalse(b.toString(Charsets.UTF_8).contains(key())); assertFalse(b.toList().windowed(32).any { it.all { v -> v == 0.toByte() } }) }
    @Test fun tamperedTagRejected() = runBlocking { val w = repository.create("").selected!!; w.envelope!!.ciphertext[0] = (w.envelope.ciphertext[0].toInt() xor 1).toByte(); disk.bytes = CatalogCodec.encode(Catalog(listOf(w), w.id)); expect(WalletError.CORRUPT) { repository.backup(w.id, Purpose.REVEAL) { fail("No export") } } }
    @Test fun wrongAadRejected() = runBlocking { val w = repository.create("").selected!!; val other = public(4); val changed = w.copy(address = other.address, publicKey = other.publicKey); disk.bytes = CatalogCodec.encode(Catalog(listOf(changed), changed.id)); expect(WalletError.CORRUPT) { repository.backup(w.id, Purpose.REVEAL) { fail("No export") } } }
    @Test fun swappedEnvelopesRejected() = runBlocking { val a = repository.create("").selected!!; val b = repository.create("").selected!!; disk.bytes = CatalogCodec.encode(Catalog(listOf(a.copy(envelope = b.envelope), b), a.id)); expect(WalletError.CORRUPT) { repository.backup(a.id, Purpose.REVEAL) { fail("No export") } } }
    @Test fun missingAliasPreservesRecords() = runBlocking { val w = repository.create("").selected!!; secrets.keys.clear(); expect(WalletError.MISSING_KEY) { repository.backup(w.id, Purpose.REVEAL) { fail("No export") } }; assertEquals(1, repository.load().wallets.size) }
    @Test fun invalidationPreservesRecords() = runBlocking { val w = repository.create("").selected!!; secrets.decryptFailure = WalletError.INVALIDATED; expect(WalletError.INVALIDATED) { repository.backup(w.id, Purpose.REVEAL) { fail("No export") } }; assertNotNull(disk.bytes) }
    @Test fun corruptCatalogFailsClosed() { disk.bytes = byteArrayOf(1, 2); expect(WalletError.CORRUPT) { repository.create("") }; assertTrue(secrets.keys.isEmpty()) }
    @Test fun unknownVersionRejected() { val b = CatalogCodec.encode(Catalog()); b[7] = 2; disk.bytes = b; expect(WalletError.VERSION) { repository.load() } }
    @Test fun trailingDataRejected() { disk.bytes = CatalogCodec.encode(Catalog()) + byteArrayOf(1); expect(WalletError.CORRUPT) { repository.load() } }
    @Test fun atomicWriteFailureRetainsPreviousCatalog() = runBlocking { val a = repository.create("").selected!!; disk.broken = true; expect(WalletError.STORAGE) { repository.rename(a.id, "changed") }; assertEquals(a.name, repo().load().selected!!.name) }
    @Test fun failedCreateCommitRetainsOrphanEvidence() { disk.broken = true; expect(WalletError.STORAGE) { repository.create("") }; assertTrue(secrets.keys.isNotEmpty()); expect(WalletError.ORPHAN) { repository.load() } }
    @Test fun failedDeleteKeepsTombstoneAndNeighbor() = runBlocking { val a = repository.create("").selected!!; val b = repository.create("").selected!!; secrets.deleteFailure = true; expect(WalletError.INCOMPLETE) { repository.remove(b.id, true) }; assertTrue(repo().load().selected!!.removing); assertTrue(secrets.keys.containsKey(a.id)); secrets.deleteFailure = false; assertEquals(a.id, repository.remove(b.id, true).selectedId) }
    @Test fun interruptedDeleteAfterAliasRemovalCanRetry() = runBlocking { val w = repository.create("").selected!!; disk.bytes = CatalogCodec.encode(Catalog(listOf(w.copy(removing = true)), w.id)); secrets.keys.clear(); assertTrue(repo().remove(w.id, true).wallets.isEmpty()) }
    @Test fun backupAlwaysNeedsNewApproval() = runBlocking { val w = repository.create("").selected!!; repeat(2) { repository.backup(w.id, Purpose.REVEAL) {} }; assertEquals(3, auth.requests) }
    @Test fun overlappingActionsRefused() = runBlocking { auth.intercept = { expect(WalletError.BUSY) { repository.create("") }; expect(WalletError.BUSY) { repository.addWatch(public(9).address, "") } }; repository.create(""); assertEquals(1, repository.load().wallets.size) }
    @Test fun invalidSelectedAndDuplicateIdsRejected() = runBlocking { val w = repository.create("").selected!!; disk.bytes = CatalogCodec.encode(Catalog(listOf(w, w), w.id)); expect(WalletError.CORRUPT) { repository.load() } }
    @Test fun yamlImportsValidPublicFixture() = runBlocking { val f = yaml(true); val c = repository.importYaml(f.first, f.second, "YAML"); assertFalse(c.selected!!.watchOnly); assertEquals(1, c.wallets.size) }
    @Test fun wrongYamlPasswordCreatesNothing() { val f = yaml(true); expect(WalletError.INVALID_YAML) { repository.importYaml(f.first, "wrong public test password", "") }; assertNull(disk.bytes); assertTrue(secrets.keys.isEmpty()) }
    @Test fun alteredYamlCreatesNothing() { val f = yaml(false); expect(WalletError.INVALID_YAML) { repository.importYaml(f.first, f.second, "") }; assertNull(disk.bytes) }
    private fun yaml(valid: Boolean): Pair<ByteArray, String> {
        val root = JsonParser.parseReader(java.io.File("../massa-core/src/test/resources/ios-parity.json").reader()).asJsonObject
        val f = root.getAsJsonArray("yamlCases").first { if (valid) it.asJsonObject["accepted"].asBoolean else it.asJsonObject["name"].asString == "corrupt-tag" }.asJsonObject
        return f["yaml"].asString.toByteArray() to f["password"].asString
    }
}
