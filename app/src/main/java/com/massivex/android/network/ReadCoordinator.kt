package com.massivex.android.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface DisplayPreferences {
    var currency: String
    var fiat: Boolean
    var filter: ActivityFilter
}
data class ReadUi(
    val walletId: String? = null, val address: String? = null,
    val balance: BalanceRead? = null, val balanceLoading: Boolean = false, val balanceError: ReadError? = null,
    val operations: List<PublicOperation> = emptyList(), val historyLoading: Boolean = false, val historyError: ReadError? = null,
    val historyLoaded: Boolean = false, val partial: Boolean = false, val cursor: HistoryCursor? = null, val pages: Int = 0,
    val quote: MarketQuote? = null, val quoteLoading: Boolean = false, val quoteError: ReadError? = null,
    val currencies: List<String> = emptyList(), val currency: String = "USD", val fiat: Boolean = false,
    val filter: ActivityFilter = ActivityFilter.ALL, val detailId: String? = null, val now: Long = 0
) {
    fun forWallet(id: String, publicAddress: String): ReadUi = if (walletId == id && address == publicAddress) this else ReadUi(walletId = id, address = publicAddress, currency = currency, fiat = fiat, filter = filter, now = now)
    val quoteUsable get() = quote?.takeIf { it.currency == currency && it.usable(now) }
    val stale get() = balance?.let { now < it.fetchedAt || now - it.fetchedAt > 60000 || balanceError != null } ?: false
}
/** Public-only state. Address selection clears synchronously; epoch checks also reject uncooperative late providers. */
class ReadCoordinator(private val scope: CoroutineScope, private val balances: BalanceReader, private val history: HistoryReader, private val market: MarketReader, private val prefs: DisplayPreferences, private val pause: suspend (Long) -> Unit = { delay(it) }, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(ReadUi(currency = prefs.currency, fiat = prefs.fiat, filter = prefs.filter, now = clock()))
    val state = mutable.asStateFlow()
    private var epoch = 0; private var marketGeneration = 0; private var foreground = false
    private var balanceJob: Job? = null; private var historyJob: Job? = null; private var marketJob: Job? = null; private var ticker: Job? = null
    private var lastBalance: Long? = null; private var lastHistory: Long? = null; private var lastMarket: Long? = null
    fun select(id: String?, address: String?) {
        if (state.value.walletId == id && state.value.address == address) return
        address?.let(::publicAddress)
        cancelWallet(); marketJob?.cancel(); marketJob = null; marketGeneration++; epoch++
        mutable.update { ReadUi(walletId = id, address = address, currency = it.currency, fiat = it.fiat, filter = it.filter, currencies = it.currencies, quote = it.quote, quoteError = it.quoteError, now = clock()) }
        if (foreground && address != null) refresh()
    }
    fun resume() {
        if (foreground) return
        foreground = true; tick()
        ticker = scope.launch { while (isActive) { delay(1000); tick() } }
        if (state.value.address != null) refresh()
    }
    fun stop() {
        foreground = false; epoch++; marketGeneration++; cancelWallet(); marketJob?.cancel(); marketJob = null; ticker?.cancel(); ticker = null
        mutable.update { it.copy(balanceLoading = false, historyLoading = false, quoteLoading = false, now = clock()) }
    }
    fun tick() { mutable.update { it.copy(now = clock()) } }
    private fun cancelWallet() { balanceJob?.cancel(); balanceJob = null; historyJob?.cancel(); historyJob = null }
    private fun ready(previous: Long?, interval: Long): Boolean = previous == null || clock() - previous >= interval || clock() < previous
    fun refresh() { refreshBalance(); refreshHistory(); refreshMarket() }
    fun refreshBalance() {
        val address = state.value.address ?: return; if (!foreground || state.value.balanceLoading) return
        if (!ready(lastBalance, 2000)) { mutable.update { it.copy(balanceError = ReadError.COOLDOWN) }; return }
        lastBalance = clock(); val token = epoch
        mutable.update { it.copy(balanceLoading = true, balanceError = null) }
        balanceJob = scope.launch {
            try { val value = balances.balance(address); checkRead(value.address == address)
                if (token == epoch && foreground) mutable.update { it.copy(balance = value, balanceLoading = false, balanceError = null, now = clock()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == epoch && foreground) mutable.update { it.copy(balanceLoading = false, balanceError = reason(e), now = clock()) } }
        }
    }
    fun refreshHistory(more: Boolean = false) {
        val address = state.value.address ?: return; if (!foreground || state.value.historyLoading) return
        val old = state.value
        if (more && (old.cursor == null || old.pages >= 10 || old.operations.size >= 500)) return
        if (!ready(lastHistory, 2000)) { mutable.update { it.copy(historyError = ReadError.COOLDOWN) }; return }
        lastHistory = clock(); val token = epoch; val cursor = if (more) old.cursor else null
        mutable.update { it.copy(historyLoading = true, historyError = null) }
        historyJob = scope.launch {
            try { val page = history.page(address, cursor)
                if (token == epoch && foreground) mutable.update {
                    it.copy(operations = ExplorerReads.merge(if (more || page.partial) old.operations else emptyList(), page.operations), historyLoading = false, historyLoaded = true,
                        historyError = null, cursor = if (more && page.cursor == cursor && !page.partial) null else page.cursor,
                        pages = if (more) old.pages + 1 else 1, partial = page.partial || page.invalidRows > 0 || page.imprecise > 0, now = clock())
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == epoch && foreground) mutable.update { it.copy(historyLoading = false, historyError = reason(e), now = clock()) } }
        }
    }
    fun refreshMarket() {
        if (!foreground || state.value.quoteLoading) return
        val cached = state.value.quote
        if (cached?.currency == state.value.currency && cached.usable(clock()) && clock() - cached.updated < 300000) return
        if (!ready(lastMarket, 15000)) {
            val remaining = (15000 - (clock() - lastMarket!!)).coerceIn(1, 15000)
            val token = epoch; val generation = ++marketGeneration
            mutable.update { it.copy(quoteLoading = true, quoteError = null) }
            marketJob = scope.launch {
                pause(remaining)
                if (foreground && token == epoch && generation == marketGeneration) {
                    mutable.update { it.copy(quoteLoading = false) }
                    refreshMarket()
                }
            }
            return
        }
        lastMarket = clock(); val currency = state.value.currency
        mutable.update { it.copy(quoteLoading = true, quoteError = null) }
        val token = epoch; val generation = ++marketGeneration
        marketJob = scope.launch {
            try {
                val codes = market.currencies(clock())
                if (foreground) mutable.update { it.copy(currencies = codes) }
                val value = market.quote(currency, clock()); checkRead(value.currency == currency)
                if (!value.usable(clock())) throw ReadFailure(ReadError.STALE)
                if (foreground && token == epoch && generation == marketGeneration && state.value.currency == currency) mutable.update { it.copy(quote = value, quoteLoading = false, quoteError = null, now = clock()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (foreground && token == epoch && generation == marketGeneration && state.value.currency == currency) mutable.update { it.copy(quoteLoading = false, quoteError = reason(e), now = clock()) } }
        }
    }
    fun currency(value: String) {
        if (value !in state.value.currencies) return
        prefs.currency = value
        if (value == state.value.currency) return
        marketGeneration++; marketJob?.cancel(); marketJob = null
        mutable.update { it.copy(currency = value, quote = null, quoteLoading = false, quoteError = null, now = clock()) }
        refreshMarket()
    }
    fun toggleFiat() { prefs.fiat = !state.value.fiat; mutable.update { it.copy(fiat = prefs.fiat) }; refreshMarket() }
    fun filter(value: ActivityFilter) { prefs.filter = value; mutable.update { it.copy(filter = value) } }
    fun detail(id: String?) { mutable.update { it.copy(detailId = id?.takeIf { value -> it.operations.any { op -> op.id == value } }) } }
    private fun reason(e: Exception) = (e as? ReadFailure)?.reason ?: ReadError.OFFLINE
}
