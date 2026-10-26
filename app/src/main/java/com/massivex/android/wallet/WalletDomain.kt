package com.massivex.android.wallet

import com.massivex.massa.*
import com.massivex.android.transfer.*
import java.io.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WalletError { INVALID_KEY, INVALID_YAML, INVALID_ADDRESS, DUPLICATE, STORAGE, CORRUPT, VERSION, MISSING_KEY, INVALIDATED, AUTH, CANCELLED, EXPIRED, LOCK_REQUIRED, UNSUPPORTED, BUSY, WATCH_ONLY, CHANGED, ORPHAN, INCOMPLETE, TRANSFER_LOCKED }
class WalletException(val reason: WalletError) : Exception(reason.name)
fun fail(reason: WalletError): Nothing = throw WalletException(reason)
fun requireWallet(ok: Boolean, reason: WalletError) { if (!ok) fail(reason) }
fun interface Clock { fun now(): Long }
fun interface Randomness { fun seed(): ByteArray }
enum class Purpose { ADD, REVEAL, COPY, REMOVE, SIGN }
data class Action(val walletId: String, val purpose: Purpose, val commitment: String? = null)
/** One-shot approval. Production issues it only after system prompt AND an auth-bound AES operation. */
class Approval internal constructor(private val action: Action, private val clock: Clock, private val issued: Long, private val alive: () -> Boolean) {
    private var used = false
    @Synchronized fun consume(expected: Action) {
        requireWallet(!used && action == expected && alive(), WalletError.CHANGED)
        used = true
        requireWallet(clock.now() - issued in 0..4000, WalletError.EXPIRED)
    }
}
interface OwnerAuthentication {
    suspend fun approve(action: Action): Approval
    fun cancel()
}
class Envelope(val version: Int, val iv: ByteArray, val ciphertext: ByteArray) {
    override fun toString() = "Envelope(<encrypted>)"
}
data class Wallet(val id: String, val name: String, val address: String, val publicKey: String?, val envelope: Envelope?, val removing: Boolean = false) {
    val watchOnly get() = publicKey == null
    fun aad(): ByteArray = "MassiveX/seed/v1\u0000$id\u0000$address\u0000$publicKey".toByteArray(Charsets.UTF_8)
}
data class Catalog(val wallets: List<Wallet> = emptyList(), val selectedId: String? = null) {
    val selected get() = wallets.firstOrNull { it.id == selectedId }
}
interface CatalogPersistence { fun read(): ByteArray?; fun write(bytes: ByteArray) }
interface SecretStorage {
    fun encrypt(wallet: Wallet, seed: ByteArray): Envelope
    fun decrypt(wallet: Wallet): ByteArray
    fun delete(id: String)
    fun walletIds(): Set<String>
    fun checkCapability()
}

/** Small bounded binary schema. Public metadata + encrypted seeds commit together. No Java serialization. */
object CatalogCodec {
    fun encode(catalog: Catalog): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { d ->
            d.writeInt(0x4d584132); d.writeInt(1); d.writeUTF(catalog.selectedId ?: ""); d.writeInt(catalog.wallets.size)
            catalog.wallets.forEach { w ->
                d.writeUTF(w.id); d.writeUTF(w.name); d.writeUTF(w.address); d.writeUTF(w.publicKey ?: ""); d.writeBoolean(w.removing)
                d.writeBoolean(w.envelope != null)
                w.envelope?.let { e -> d.writeInt(e.version); d.writeInt(e.iv.size); d.write(e.iv); d.writeInt(e.ciphertext.size); d.write(e.ciphertext) }
            }
        }
    }.toByteArray()
    fun decode(bytes: ByteArray): Catalog {
        try {
            requireWallet(bytes.size <= 262144, WalletError.CORRUPT)
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                requireWallet(d.readInt() == 0x4d584132, WalletError.CORRUPT)
                requireWallet(d.readInt() == 1, WalletError.VERSION)
                val selected = d.readUTF().ifEmpty { null }; val count = d.readInt()
                requireWallet(count in 0..100, WalletError.CORRUPT)
                val wallets = List(count) {
                    val id = d.readUTF(); val name = d.readUTF(); val address = d.readUTF(); val public = d.readUTF().ifEmpty { null }; val removing = d.readBoolean()
                    val envelope = if (d.readBoolean()) {
                        val version = d.readInt(); requireWallet(version == 1, WalletError.VERSION)
                        requireWallet(d.readInt() == 12, WalletError.CORRUPT); val iv = ByteArray(12); d.readFully(iv)
                        requireWallet(d.readInt() == 48, WalletError.CORRUPT); val cipher = ByteArray(48); d.readFully(cipher)
                        Envelope(version, iv, cipher)
                    } else null
                    requireWallet(UUID.fromString(id).toString() == id && name.isNotBlank() && name.length <= 40, WalletError.CORRUPT)
                    MassaCodec.validateAddress(address, userOnly = true)
                    requireWallet((public == null) == (envelope == null), WalletError.CORRUPT)
                    if (public != null) requireWallet(PublicAccount.fromRaw(MassaCodec.decode(public, "P")).address == address, WalletError.CORRUPT)
                    Wallet(id, name, address, public, envelope, removing)
                }
                requireWallet(d.available() == 0 && wallets.map { it.id }.toSet().size == count && wallets.map { it.address }.toSet().size == count, WalletError.CORRUPT)
                requireWallet(if (count == 0) selected == null else wallets.any { it.id == selected }, WalletError.CORRUPT)
                return Catalog(wallets, selected)
            }
        } catch (e: WalletException) { throw e } catch (_: Exception) { fail(WalletError.CORRUPT) }
    }
}

fun interface WalletRemovalGuard { fun begin(wallet: Wallet): AutoCloseable }

/** Operations are serialized by the UI and this repository; no unlocked account is retained. */
class WalletRepository(private val persistence: CatalogPersistence, private val secrets: SecretStorage, private val random: Randomness, private val authentication: OwnerAuthentication, private val defaultName: (Int) -> String = { if (it == 1) "Wallet" else "Wallet $it" }, private val removalGuard: WalletRemovalGuard = WalletRemovalGuard { AutoCloseable {} }) {
    private var busy = false
    fun load(): Catalog {
        val catalog = persistence.read()?.let(CatalogCodec::decode) ?: Catalog()
        requireWallet((secrets.walletIds() - catalog.wallets.filter { !it.watchOnly }.map { it.id }.toSet()).isEmpty(), WalletError.ORPHAN)
        return catalog
    }
    private fun save(c: Catalog) { persistence.write(CatalogCodec.encode(c)) }
    private fun name(input: String, c: Catalog): String {
        val cleaned = input.trim().take(40)
        if (cleaned.isNotEmpty()) return cleaned
        var index = 1
        while (true) { val n = defaultName(index); if (c.wallets.none { it.name == n }) return n; index++ }
    }
    private suspend fun <T> serialized(block: suspend () -> T): T {
        requireWallet(!busy, WalletError.BUSY); busy = true
        try { return block() } finally { busy = false }
    }
    suspend fun create(name: String): Catalog = serialized {
        secrets.checkCapability()
        val seed = random.seed()
        try { addSeed(seed, name) } finally { seed.fill(0) }
    }
    suspend fun importPrivate(input: String, name: String): Catalog = serialized {
        secrets.checkCapability()
        val seed = try { MassaCodec.decode(input.trim(), "S") } catch (_: Exception) { fail(WalletError.INVALID_KEY) }
        try { addSeed(seed, name) } finally { seed.fill(0) }
    }
    suspend fun importYaml(bytes: ByteArray, password: String, name: String): Catalog = serialized {
        secrets.checkCapability()
        var imported: OfflineAccount? = null
        try {
            try {
                // Keep ownership even if cancellation discards the dispatched result on return.
                withContext(Dispatchers.Default) { imported = MassaYAML.decrypt(MassaYAML.parse(bytes), password) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { fail(WalletError.INVALID_YAML) }
            val account = imported ?: fail(WalletError.INVALID_YAML)
            // Existing core exposes canonical export, not a raw seed accessor. This temporary String cannot be zeroized.
            val seed = MassaCodec.decode(account.privateKeyEncoding(), "S")
            try { addSeed(seed, name) } finally { seed.fill(0) }
        } finally { imported?.close() }
    }
    private suspend fun addSeed(seed: ByteArray, input: String): Catalog {
        val old = load(); requireWallet(old.wallets.size < 100, WalletError.STORAGE)
        val public = OfflineAccount.fromSeed(seed).use { it.publicAccount() }
        requireWallet(old.wallets.none { it.address == public.address }, WalletError.DUPLICATE)
        val draft = Wallet(UUID.randomUUID().toString(), name(input, old), public.address, public.publicKey, null)
        val action = Action(draft.id, Purpose.ADD)
        val approval = authentication.approve(action); approval.consume(action)
        val encrypted = secrets.encrypt(draft, seed)
        // If commit fails, retain alias as evidence; load detects it. Never discard a possibly committed key.
        val wallet = draft.copy(envelope = encrypted)
        return Catalog(old.wallets + wallet, wallet.id).also { save(it) }
    }
    fun addWatch(address: String, input: String): Catalog {
        requireWallet(!busy, WalletError.BUSY)
        val clean = address.trim()
        try { MassaCodec.validateAddress(clean, userOnly = true) } catch (_: Exception) { fail(WalletError.INVALID_ADDRESS) }
        val old = load(); requireWallet(old.wallets.size < 100, WalletError.STORAGE)
        requireWallet(old.wallets.none { it.address == clean }, WalletError.DUPLICATE)
        val w = Wallet(UUID.randomUUID().toString(), name(input, old), clean, null, null)
        return Catalog(old.wallets + w, w.id).also { save(it) }
    }
    fun select(id: String): Catalog {
        requireWallet(!busy, WalletError.BUSY)
        val old = load(); requireWallet(old.wallets.any { it.id == id }, WalletError.CHANGED)
        return old.copy(selectedId = id).also { save(it) }
    }
    fun rename(id: String, input: String): Catalog {
        requireWallet(!busy, WalletError.BUSY)
        val old = load(); requireWallet(old.selectedId == id && input.trim().isNotEmpty(), WalletError.CHANGED)
        return old.copy(wallets = old.wallets.map { if (it.id == id) it.copy(name = name(input, old)) else it }).also { save(it) }
    }
    suspend fun backup(id: String, purpose: Purpose, consume: (String) -> Unit) = serialized {
        requireWallet(purpose == Purpose.REVEAL || purpose == Purpose.COPY, WalletError.CHANGED)
        val old = load(); val wallet = old.selected ?: fail(WalletError.CHANGED)
        requireWallet(wallet.id == id && !wallet.removing, WalletError.CHANGED)
        requireWallet(!wallet.watchOnly, WalletError.WATCH_ONLY)
        val action = Action(id, purpose); authentication.approve(action).consume(action)
        val seed = secrets.decrypt(wallet)
        try {
            OfflineAccount.fromSeed(seed).use { account ->
                requireWallet(account.publicAccount() == PublicAccount(wallet.publicKey!!, wallet.address), WalletError.CORRUPT)
                consume(account.privateKeyEncoding())
            }
        } finally { seed.fill(0) }
    }
    /** Only an immutable, validated transfer can use the seed path; no sign(data) API. */
    internal suspend fun signReviewed(draft: ReviewedTransfer, now: () -> Long = System::currentTimeMillis): SignedTransfer = serialized {
        secrets.checkCapability(); draft.validate()
        requireWallet(age(now(),draft.reviewedAt), WalletError.EXPIRED)
        fun current(): Wallet {
            val w=load().selected ?: fail(WalletError.CHANGED)
            requireWallet(!w.watchOnly && !w.removing && TransferSource.capture(w)==draft.source,WalletError.CHANGED)
            return w
        }
        current()
        val action=Action(draft.source.walletId,Purpose.SIGN,draft.commitment)
        authentication.approve(action).consume(action)
        val wallet=current();requireWallet(age(now(),draft.reviewedAt),WalletError.EXPIRED)
        val seed=secrets.decrypt(wallet)
        try {
            OfflineAccount.fromSeed(seed).use { account ->
                val expected=PublicAccount(draft.source.publicKey,draft.source.address)
                requireWallet(account.publicAccount()==expected,WalletError.CORRUPT)
                SignedTransfer(draft,account.signOperation(draft.content,expected)).also { it.validate() }
            }
        } finally { seed.fill(0) }
    }
    suspend fun remove(id: String, acknowledged: Boolean): Catalog = serialized {
        val old = load(); val wallet = old.selected ?: fail(WalletError.CHANGED)
        requireWallet(acknowledged && wallet.id == id, WalletError.CHANGED)
        val lease = removalGuard.begin(wallet)
        try {
        if (!wallet.watchOnly) { val action = Action(id, Purpose.REMOVE); authentication.approve(action).consume(action) }
        val pending = old.copy(wallets = old.wallets.map { if (it.id == id) it.copy(removing = true) else it })
        save(pending) // Durable intent before irreversible alias removal; retry is idempotent.
        try {
            if (!wallet.watchOnly) secrets.delete(id)
            val remaining = old.wallets.filter { it.id != id }
            Catalog(remaining, remaining.firstOrNull()?.id).also { save(it) }
        } catch (_: Exception) { fail(WalletError.INCOMPLETE) }
        } finally { lease.close() }
    }
}
