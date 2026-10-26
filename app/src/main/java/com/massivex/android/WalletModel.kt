package com.massivex.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.massivex.android.wallet.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class Screen { HOME, ADD, CREATE, PRIVATE, YAML, WATCH, BACKUP, RENAME, REMOVE, SETTINGS, RECEIVE, ACTIVITY, OPERATION, CURRENCY, SEND }
data class WalletUi(val catalog: Catalog = Catalog(), val screen: Screen = Screen.HOME, val busy: Boolean = false, val error: WalletError? = null, val privacyEpoch: Int = 0, val blocked: Boolean = false)
class WalletModel : ViewModel() {
    private val mutable = MutableStateFlow(WalletUi())
    val state = mutable.asStateFlow()
    private lateinit var repository: WalletRepository
    private var authentication: OwnerAuthentication? = null
    private var job: Job? = null
    private var masking: (() -> Unit)? = null
    fun attach(repo: WalletRepository, auth: OwnerAuthentication) {
        background(); repository = repo; authentication = auth
        refresh()
    }
    private fun refresh() {
        try { mutable.update { it.copy(catalog = repository.load(), blocked = false) } }
        catch (e: Exception) { mutable.update { it.copy(error = reason(e), blocked = true) } }
    }
    fun privacyHandler(handler: (() -> Unit)?) { masking = handler }
    fun obscure() { masking?.invoke() }
    fun background() {
        authentication?.cancel(); job?.cancel(); job = null; masking?.invoke()
        mutable.update { it.copy(busy = false, privacyEpoch = it.privacyEpoch + 1) }
    }
    fun navigate(screen: Screen) {
        if (mutable.value.busy) return
        masking?.invoke(); mutable.update { it.copy(screen = screen, error = null) }
    }
    fun run(destination: Screen = Screen.HOME, operation: suspend (WalletRepository) -> Catalog?) {
        if (mutable.value.busy || mutable.value.blocked) return
        mutable.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                operation(repository)?.let { catalog -> mutable.update { it.copy(catalog = catalog) } }
                mutable.update { it.copy(screen = destination) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { refresh(); mutable.update { it.copy(error = reason(e)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun reason(e: Exception) = (e as? WalletException)?.reason ?: WalletError.STORAGE
    override fun onCleared() { background(); masking = null }
}
