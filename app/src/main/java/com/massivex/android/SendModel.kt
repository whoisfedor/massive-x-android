package com.massivex.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.massivex.android.transfer.*
import com.massivex.android.wallet.*
import kotlinx.coroutines.*

internal class SendModel(app:Application):AndroidViewModel(app) {
    val ledger=ProcessTransferStore.ledger(app)
    private val reads=MainnetSendReads()
    val coordinator=SendCoordinator(reads,ledger,reads,MainnetBroadcastTransport()) // Identical owner-gesture path in debug and release.
    private var signer:TransferSigner?=null
    private var action:Job?=null
    private var polling:Job?=null
    private var foreground=false
    fun attach(repository:WalletRepository,authentication:OwnerAuthentication){background();signer=ProtectedWalletSigner(repository,authentication)}
    fun select(wallet:Wallet?){
        val source=wallet?.takeUnless{it.watchOnly||it.removing}?.let{runCatching{TransferSource.capture(it)}.getOrNull()}
        if(coordinator.state.value.source==source)return
        action?.cancel();polling?.cancel();coordinator.select(source)
        if(foreground)poll()
    }
    fun resume(){foreground=true;coordinator.resume();poll()}
    private fun poll(){polling?.cancel();polling=viewModelScope.launch{coordinator.reload();coordinator.pollSelected()}}
    fun edit(){action?.cancel();coordinator.invalidate(keepPreview=true)}
    fun prepare(recipient:String,amount:String){if(!foreground||coordinator.state.value.busy)return;polling?.cancel();action=viewModelScope.launch{coordinator.prepare(recipient,amount)}}
    fun preview(recipient:String){if(!foreground||coordinator.state.value.busy)return;polling?.cancel();action=viewModelScope.launch{coordinator.preview(recipient)}}
    fun maximum(recipient:String){if(!foreground||coordinator.state.value.busy)return;polling?.cancel();action=viewModelScope.launch{coordinator.maximum(recipient)}}
    fun confirm(review:ReviewedTransfer){if(!foreground||coordinator.state.value.busy)return;val protected=signer?:return;polling?.cancel();action=viewModelScope.launch{coordinator.confirm(review,protected)}}
    fun background(){foreground=false;action?.cancel();polling?.cancel();coordinator.stop();signer?.cancel()}
    override fun onCleared(){background()}
}
