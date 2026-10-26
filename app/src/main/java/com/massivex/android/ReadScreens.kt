package com.massivex.android

import android.content.Intent
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.withStyle
import android.graphics.Bitmap
import com.massivex.android.presentation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.massivex.android.network.*
import com.massivex.android.receive.ReceiveQr
import com.massivex.android.wallet.Wallet
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Currency
import java.util.Locale

@Composable
internal fun ReadSummary(wallet: Wallet, observed: ReadUi, reads: ReadCoordinator, navigate: (Screen) -> Unit, copy: (String)->Unit = {}, balanceHidden:Boolean?=null, visibility:((Boolean)->Unit)?=null, choose:(()->Unit)?=null) {
    val locale=LocalConfiguration.current.locales[0]
    var localHidden by remember(wallet.id){mutableStateOf(false)}
    val hidden=balanceHidden?:localHidden

    var info by remember{mutableStateOf(false)}
    var restricted by remember{mutableStateOf(false)}
    val state=observed.forWallet(wallet.id,wallet.address)
    val balance=state.balance;val quote=state.quoteUsable
    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=26.dp),horizontalArrangement=Arrangement.Center){
        Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.primaryContainer){
            TextButton(onClick={choose?.invoke()},modifier=Modifier.heightIn(min=44.dp)){Text(wallet.name+if(choose!=null)" ⌄" else "",fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)}
        }
    }
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        Text(stringResource(R.string.ui_total_balance),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp)
        MXIconAction(MXGlyph.EYE,stringResource(if(hidden)R.string.show_balance else R.string.hide_balance)){if(visibility!=null)visibility(!hidden)else localHidden=!hidden}
        Spacer(Modifier.weight(1f))
        MXIconAction(MXGlyph.INFO,stringResource(R.string.ui_balance_info)){info=true}
    }
    val amount=when{
        balance==null->stringResource(if(state.balanceLoading)R.string.read_loading else R.string.read_unavailable)
        state.fiat&&quote!=null->quote.value(balance.confirmed.nano)+" "+state.currency
        else->BigDecimal(balance.confirmed.nano.toString()).movePointLeft(9).setScale(2,RoundingMode.HALF_EVEN).toPlainString()+" MAS"
    }
    Text(androidx.compose.ui.text.buildAnnotatedString{
        val display=if(hidden)"••••"else localizedDecimal(amount,locale)
        if(balance!=null&&!hidden&&display.contains(' ')){append(display.substringBeforeLast(' '));withStyle(androidx.compose.ui.text.SpanStyle(fontSize=18.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)){append(" "+display.substringAfterLast(' '))}}else append(display)
    },Modifier.fillMaxWidth().clickable(role=androidx.compose.ui.semantics.Role.Button,onClick=reads::toggleFiat),fontSize=if(balance==null)23.sp else 34.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,lineHeight=42.sp)
    Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=20.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        NetworkDot(state)
        Spacer(Modifier.width(6.dp))
        Text("Mainnet",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
        PublicCopy(wallet.address,copy,true)
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
        MXButton(stringResource(R.string.send),modifier=Modifier.weight(1f),primary=false,icon=MXGlyph.OUT){if(!wallet.watchOnly&&android.os.Build.VERSION.SDK_INT>=30)navigate(Screen.SEND)else restricted=true}
        MXButton(stringResource(R.string.receive),modifier=Modifier.weight(1f),primary=false,icon=MXGlyph.IN){navigate(Screen.RECEIVE)}
    }
    Text(stringResource(R.string.ui_assets),Modifier.padding(top=26.dp,bottom=12.dp),fontSize=17.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
    MXPanel{
        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Image(androidx.compose.ui.res.painterResource(R.drawable.massa_currency),contentDescription=null,modifier=Modifier.size(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f)){Text("Massa",fontSize=16.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold);Text("MAS",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Text(if(hidden)"••••"else balance?.let{localizedDecimal(BigDecimal(it.confirmed.nano.toString()).movePointLeft(9).setScale(2,RoundingMode.HALF_EVEN).toPlainString(),locale)}?:"—",fontSize=16.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
    }
    if(info)BalanceDetailsSheet(state,reads::refresh,reads::toggleFiat,{info=false;navigate(Screen.CURRENCY)}){info=false}
    if(restricted)AlertDialog(onDismissRequest={restricted=false},text={Text(stringResource(if(wallet.watchOnly)R.string.receive_watch else R.string.send_error_wallet))},confirmButton={TextButton(onClick={restricted=false}){Text(stringResource(R.string.ui_done))}})
}

@Composable
internal fun ReceiveScreen(address:String,watchOnly:Boolean,copy:(String)->Unit){
    var copied by remember(address){mutableStateOf(false)}
    LaunchedEffect(copied){if(copied){kotlinx.coroutines.delay(2000);copied=false}}
    val image=remember(address){Bitmap.createBitmap(ReceiveQr.pixels(address),256,256,Bitmap.Config.ARGB_8888).asImageBitmap()}
    val context=LocalContext.current;val shareTitle=stringResource(R.string.share_address)
    Column(Modifier.fillMaxWidth(),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(22.dp)){
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){MXIcon(MXGlyph.GLOBE);Text(stringResource(R.string.ui_mainnet),color=MaterialTheme.colorScheme.primary,fontSize=16.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)}
        Surface(Modifier.padding(vertical=2.dp).widthIn(max=292.dp).fillMaxWidth(),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp),color=androidx.compose.ui.graphics.Color.White){Image(image,contentDescription=stringResource(R.string.qr_description),modifier=Modifier.padding(16.dp).fillMaxWidth().aspectRatio(1f),filterQuality=FilterQuality.None)}
        PublicCopy(address,copy,modifier=Modifier.fillMaxWidth(),balanced=true)
        if(watchOnly)Text(stringResource(R.string.receive_watch),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        MXButton(stringResource(if(copied)R.string.address_copied else R.string.copy_address)){copy(address);copied=true}
        TextButton(onClick={context.startActivity(Intent.createChooser(publicAddressShare(address),shareTitle))}){MXIcon(MXGlyph.SHARE);Spacer(Modifier.width(8.dp));Text(shareTitle)}
    }
}
internal fun publicAddressShare(address:String):Intent{
    com.massivex.massa.MassaCodec.validateAddress(address,userOnly=true)
    return Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,address)}
}

@Composable
internal fun ActivityScreen(state:ReadUi,reads:ReadCoordinator,navigate:(Screen)->Unit){
    var info by remember{mutableStateOf(false)}
    MXHeading(stringResource(R.string.activity)){
        MXIconAction(MXGlyph.INFO,stringResource(R.string.activity_info)){info=true}
        MXIconAction(MXGlyph.REFRESH,stringResource(R.string.refresh_activity),!state.historyLoading){reads.refreshHistory()}
    }
    ActivitySegments(state.filter,reads::filter)
    Spacer(Modifier.height(28.dp))
    HistoryPanel(state,reads,navigate)
    if(info)ActivityDetailsSheet(state,{reads.refreshHistory()}){info=false}
}
@Composable internal fun ActivitySegments(selected:ActivityFilter,choose:(ActivityFilter)->Unit){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        ActivityFilter.entries.forEach{filter->
            val active=selected==filter
            val accessibleLabel=stringResource(filterLabel(filter))
            Surface(onClick={choose(filter)},modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics{role=androidx.compose.ui.semantics.Role.Tab;this.selected=active;contentDescription=accessibleLabel},shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),color=if(active)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,if(active)MaterialTheme.colorScheme.primary.copy(alpha=.6f)else MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))){
                Box(Modifier.padding(vertical=12.dp),contentAlignment=androidx.compose.ui.Alignment.Center){when(filter){ActivityFilter.SENT->Box(Modifier.semantics{contentDescription=""}){MXIcon(MXGlyph.OUT,color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)};ActivityFilter.RECEIVED->MXIcon(MXGlyph.IN,color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);else->Text(stringResource(filterLabel(filter)),fontSize=13.sp,color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}
            }
        }
    }
}
@Composable internal fun HistoryPanel(state:ReadUi,reads:ReadCoordinator,navigate:(Screen)->Unit,limit:Int=500){
    val locale=LocalConfiguration.current.locales[0];val address=state.address
    val items=if(address==null)emptyList()else state.operations.filter{state.filter.includes(it,address)}.take(limit)
    MXPanel{
        if(state.historyLoading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(state.partial&&state.historyError==null)Text(stringResource(R.string.read_unavailable),Modifier.padding(16.dp),fontSize=12.sp)
        if(state.historyError!=null)Text(stringResource(R.string.activity_unavailable),Modifier.padding(20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
        if(items.isEmpty()&&state.historyError==null)Text(stringResource(if(state.historyLoading||!state.historyLoaded)R.string.read_loading else R.string.activity_empty),Modifier.padding(24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)
        items.forEachIndexed{index,op->
            if(index>0)HorizontalDivider(Modifier.padding(start=56.dp),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
            val direction=op.direction(address!!);val counterpart=if(op.sender==address)op.recipient?:op.sender else op.sender
            Row(Modifier.fillMaxWidth().clickable(role=androidx.compose.ui.semantics.Role.Button){reads.detail(op.id);navigate(Screen.OPERATION)}.padding(horizontal=16.dp,vertical=18.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                MXIcon(if(direction==OperationDirection.INCOMING)MXGlyph.IN else MXGlyph.OUT,Modifier.size(20.dp))
                Column(Modifier.weight(1f)){Text(stringResource(directionLabel(direction)),fontSize=14.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium);Text(counterpart.take(6)+"…"+counterpart.takeLast(5),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                Column(Modifier.weight(1f),horizontalAlignment=androidx.compose.ui.Alignment.End){Text(op.amount?.let{localizedDecimal(it.text,locale)+" MAS"}?:"—",fontSize=12.sp);Text(op.millis?.let{localizedTime(it,locale)}?:stringResource(R.string.time_unknown),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                MXIcon(MXGlyph.CHEVRON,Modifier.size(12.dp),MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(limit==500&&state.cursor!=null&&state.pages<10&&state.operations.size<500)TextButton(onClick={reads.refreshHistory(true)},enabled=!state.historyLoading){Text(stringResource(R.string.load_more))}
        if(state.pages>=10||state.operations.size>=500)Text(stringResource(R.string.activity_limit),Modifier.padding(16.dp),fontSize=12.sp)
    }
}

@Composable
internal fun OperationScreen(state: ReadUi, copy: (String) -> Unit) {
    val locale=LocalConfiguration.current.locales[0]
    val op=state.operations.firstOrNull{it.id==state.detailId}?:return
    val context=LocalContext.current
    Text(stringResource(R.string.operation_details).uppercase(),style=MaterialTheme.typography.labelSmall)
    MXPanel{Column(Modifier.padding(16.dp)){
        PublicCopy(op.copyableId,copy)
        DetailValue(stringResource(R.string.ui03_status),op.providerStatus+" · "+stringResource(R.string.ui03_observed))
        HorizontalDivider()
        DetailValue(stringResource(R.string.ui03_block_time),op.millis?.let{localizedTime(it,locale)}?:stringResource(R.string.time_unknown))
        HorizontalDivider()
        DetailValue(stringResource(R.string.ui_amount),op.amount?.let{localizedDecimal(it.text,locale)+" MAS"}?:"—")
        DetailValue(stringResource(R.string.ui_network_fee),op.fee?.let{localizedDecimal(it.text,locale)+" MAS"}?:"—")
        if(op.id!=op.copyableId)PublicCopy(op.id,copy)
        if(op.identity.parent==null)Text(stringResource(R.string.no_chain_operation),style=MaterialTheme.typography.bodySmall)
    }}
    Text(stringResource(R.string.ui03_addresses).uppercase(),style=MaterialTheme.typography.labelSmall)
    MXPanel{Column(Modifier.padding(16.dp)){
        Text(stringResource(R.string.ui_from),color=MaterialTheme.colorScheme.onSurfaceVariant);PublicCopy(op.sender,copy)
        op.recipient?.let{HorizontalDivider();Text(stringResource(R.string.ui_recipient),color=MaterialTheme.colorScheme.onSurfaceVariant);PublicCopy(it,copy)}
    }}
    op.explorerUrl?.let{url->MXRow(stringResource(R.string.open_explorer),MXGlyph.SHARE){openExternal(context,url)}}
    var information by remember{mutableStateOf(false)}
    MXIconAction(MXGlyph.INFO,stringResource(R.string.activity_info)){information=true}
    if(information)AlertDialog(onDismissRequest={information=false},text={Text(stringResource(R.string.ui_history_note))},confirmButton={TextButton(onClick={information=false}){Text(stringResource(R.string.ui_done))}})
}

@Composable
internal fun CurrencyScreen(state: ReadUi, reads: ReadCoordinator) {
    val locale=LocalConfiguration.current.locales[0]
    var query by remember { mutableStateOf("") }
    OutlinedTextField(query, { query = it.take(40) }, label = { Text(stringResource(R.string.search_currency)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    if (state.currencies.isEmpty()) {
        Text(stringResource(R.string.currencies_unavailable))
        ReadButton(R.string.retry_currencies, !state.quoteLoading) { reads.refreshMarket() }
    }
    state.currencies.filter { (it + " " + currencyName(it,locale)).contains(query, ignoreCase = true) }.forEach { code ->
        TextButton(onClick = { reads.currency(code) }) { Text(stringResource(R.string.currency_option, code, currencyName(code,locale), if (state.currency == code) "✓" else "")) }
    }
}
private fun currencyName(code: String,locale:Locale): String = when (code) {
    "BTC" -> "Bitcoin"; "ETH" -> "Ethereum"; "SOL" -> "Solana"
    else -> runCatching { Currency.getInstance(code).getDisplayName(locale) }.getOrDefault(code)
}
@Composable internal fun ReadButton(label: Int, enabled: Boolean, action: () -> Unit) { Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) } }
private fun filterLabel(filter: ActivityFilter) = when (filter) { ActivityFilter.ALL -> R.string.filter_all; ActivityFilter.SENT -> R.string.filter_sent; ActivityFilter.RECEIVED -> R.string.filter_received; ActivityFilter.ASC -> R.string.filter_asc }
private fun directionLabel(direction: OperationDirection) = when (direction) { OperationDirection.INCOMING -> R.string.filter_received; OperationDirection.OUTGOING -> R.string.filter_sent; OperationDirection.SELF -> R.string.self_transfer; OperationDirection.CONTRACT -> R.string.contract_interaction; OperationDirection.OTHER -> R.string.other_operation }
internal fun readErrorString(error: ReadError) = when (error) {
    ReadError.OFFLINE -> R.string.read_offline; ReadError.TIMEOUT -> R.string.read_timeout; ReadError.TLS -> R.string.read_tls
    ReadError.REDIRECT -> R.string.read_redirect; ReadError.HTTP -> R.string.read_http; ReadError.RATE_LIMIT -> R.string.read_rate_limit
    ReadError.COOLDOWN -> R.string.read_cooldown; ReadError.WRONG_NETWORK -> R.string.read_wrong_network
    ReadError.INVALID, ReadError.TOO_LARGE -> R.string.read_invalid; ReadError.UNSUPPORTED -> R.string.quote_unsupported; ReadError.STALE -> R.string.quote_unavailable
}

@Composable internal fun RecentActivity(state:ReadUi,reads:ReadCoordinator,navigate:(Screen)->Unit){
    var info by remember{mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().padding(top=24.dp,bottom=12.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        Text(stringResource(R.string.recent_activity),Modifier.weight(1f),fontSize=17.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
        MXIconAction(MXGlyph.REFRESH,stringResource(R.string.refresh_activity),!state.historyLoading){reads.refreshHistory()}
        MXIconAction(MXGlyph.INFO,stringResource(R.string.activity_info)){info=true}
        TextButton(onClick={navigate(Screen.ACTIVITY)}){Text(stringResource(R.string.ui_see_all),fontSize=12.sp)}
    }
    MXPanel{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){ActivitySegments(state.filter,reads::filter);HistoryPanel(state,reads,navigate,3)}}
    if(info)ActivityDetailsSheet(state,{reads.refreshHistory()}){info=false}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ActivityDetailsSheet(state:ReadUi,refresh:()->Unit={},toggleFiat:(()->Unit)?=null,currency:(()->Unit)?=null,close:()->Unit){
    var details by remember{mutableStateOf(false)}
    ModalBottomSheet(onDismissRequest=close,dragHandle=null){Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(stringResource(R.string.ui_activity_details),Modifier.weight(1f),fontSize=28.sp);TextButton(onClick=close){Text(stringResource(R.string.ui_done))}}
        MXPanel{Column(Modifier.padding(16.dp)){
            DetailValue(stringResource(R.string.ui_fetched),state.balance?.let{localizedTime(it.fetchedAt,LocalConfiguration.current.locales[0])}?:"—")
            if(state.stale)Text(stringResource(R.string.ui_cached),fontSize=13.sp)
            if(state.balanceError!=null||state.historyError!=null)Text(stringResource(R.string.read_unavailable),fontSize=13.sp)
            if(state.partial)Text(stringResource(R.string.activity_partial),fontSize=13.sp)
        }}
        TextButton(onClick=refresh){Text(stringResource(R.string.refresh_reads))}
        Spacer(Modifier.height(24.dp))
    }}
}

/** Fresh, successfully validated Mainnet status only; never infer connectivity from a cached balance. */
internal fun networkHealthy(state:ReadUi):Boolean = state.balance!=null && !state.stale && state.balanceError==null && state.now>=state.balance.fetchedAt && state.now-state.balance.fetchedAt<=60000
@Composable private fun NetworkDot(state:ReadUi){
    val healthy=networkHealthy(state)
    var alpha by remember{mutableFloatStateOf(1f)}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(healthy,lifecycle){
        alpha=1f
        if(healthy)lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED){
            while(true){
                alpha=if(android.animation.ValueAnimator.areAnimatorsEnabled()&&alpha==1f).35f else 1f
                kotlinx.coroutines.delay(1200)
            }
        }
    }
    androidx.compose.foundation.Canvas(Modifier.size(6.dp)) {drawCircle((if(healthy)androidx.compose.ui.graphics.Color(0xFF35C879)else if(state.balanceError!=null)androidx.compose.ui.graphics.Color(0xFFB95050)else androidx.compose.ui.graphics.Color.Gray).copy(alpha=if(healthy)alpha else 1f))}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BalanceDetailsSheet(state:ReadUi,refresh:()->Unit,toggle:()->Unit,currency:()->Unit,close:()->Unit){
    ModalBottomSheet(onDismissRequest=close,dragHandle=null){Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(MX.sectionGap)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(stringResource(R.string.ui_total_balance),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);TextButton(onClick=close){Text(stringResource(R.string.ui_done))}}
        MXPanel{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            state.balance?.let{Text(stringResource(R.string.confirmed_exact,it.confirmed.text));Text(stringResource(R.string.candidate_value,it.candidate.text))}?:Text(stringResource(R.string.read_unavailable))
            if(state.stale)Text(stringResource(R.string.read_unavailable),color=MaterialTheme.colorScheme.error)
            if(state.quoteUsable==null)Text(stringResource(R.string.quote_unavailable),style=MaterialTheme.typography.bodySmall)
        }}
        MXRow(stringResource(if(state.fiat)R.string.show_mas else R.string.show_fiat),MXGlyph.CURRENCY,onClick=toggle)
        MXRow(stringResource(R.string.currency_value,state.currency),MXGlyph.CURRENCY,onClick=currency)
        TextButton(onClick=refresh){Text(stringResource(R.string.refresh_reads))}
        Spacer(Modifier.height(24.dp))
    }}
}
