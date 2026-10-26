package com.massivex.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.massivex.android.transfer.*
import com.massivex.android.wallet.Wallet
import kotlin.math.roundToInt

/** Amount editing changes display only; canonical validation still rejects malformed/excess precision. */
internal fun normalizeAmountEdit(value:androidx.compose.ui.text.input.TextFieldValue):androidx.compose.ui.text.input.TextFieldValue =
    value.copy(text=value.text.take(40).replace(',', '.')) // one-for-one replacement preserves selection/composition

@Composable
internal fun SendScreen(wallet:Wallet,state:SendUi,prepare:(String,String)->Unit,maximum:(String)->Unit,preview:(String)->Unit,edit:()->Unit,confirm:(ReviewedTransfer)->Unit,
    balance:String?=null,copy:(String)->Unit={},close:()->Unit={},checkStatus:()->Unit={},balanceLoading:Boolean=false){
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    fun mas(value:String)=com.massivex.android.presentation.localizedDecimal(value,locale)+" MAS"
    val source=runCatching{TransferSource.capture(wallet)}.getOrNull()
    val observed=state.forSource(source)
    var recipient by remember(wallet.id){mutableStateOf("")}
    var amount by remember(wallet.id){mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))}
    @Suppress("DEPRECATION") val clipboard=LocalClipboardManager.current
    LaunchedEffect(observed.maximum){observed.maximum?.let{amount=androidx.compose.ui.text.input.TextFieldValue(it,androidx.compose.ui.text.TextRange(it.length))}}
    val record=observed.pending ?: observed.latest?.takeIf{observed.phase==SendPhase.STATUS}
    val latestPreview by rememberUpdatedState(preview)
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(wallet.id,recipient,record?.operationId,observed.phase){
        if(record==null&&observed.phase==SendPhase.FORM&&runCatching{SendInput.recipient(recipient)}.isSuccess){
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED){
                kotlinx.coroutines.delay(350)
                while(true){latestPreview(recipient);kotlinx.coroutines.delay(10_000)}
            }
        }
    }
    if(record!=null){
        var detailsOpen by remember(record.operationId){mutableStateOf(false)}
        TransferStatus(record)
        MXRow(stringResource(R.string.operation_details),MXGlyph.ACTIVITY){detailsOpen=!detailsOpen}
        if(detailsOpen)MXPanel{Column(Modifier.padding(16.dp)){
            PublicCopy(record.operationId,copy)
            PublicCopy(record.signed.draft.recipient,copy)
            DetailValue(stringResource(R.string.ui_amount),mas(record.signed.draft.amount.text))
            DetailValue(stringResource(R.string.ui_network_fee),mas(record.signed.draft.fee.text))
        }}
        MXButton(stringResource(R.string.ui_back_wallet),action=close)
        if(!record.state.terminal)TextButton(onClick=checkStatus,enabled=!observed.busy){Text(stringResource(R.string.ui_check_status))}
        else TextButton(onClick=edit){Text(stringResource(R.string.ui_new_transfer))}
    }else if(observed.review!=null && observed.phase!=SendPhase.FORM){
        val review=observed.review
        Column(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)){
            Surface(shape=RoundedCornerShape(20.dp),color=androidx.compose.ui.graphics.Color(0xFFF32242)){androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.massa_currency),null,Modifier.size(72.dp).padding(8.dp))}
            Text(stringResource(R.string.ui_mas_transfer),color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(androidx.compose.ui.text.buildAnnotatedString{append(com.massivex.android.presentation.localizedDecimal(review.amount.text,locale));pushStyle(androidx.compose.ui.text.SpanStyle(fontSize=16.sp,color=MaterialTheme.colorScheme.onSurfaceVariant));append(" MAS");pop()},fontSize=36.sp)
        }
        MXPanel{Column(Modifier.padding(horizontal=16.dp)){
            ReviewAddress(stringResource(R.string.ui_from),review.source.address,copy)
            ReviewAddress(stringResource(R.string.ui_recipient),review.recipient,copy)
            HorizontalDivider();DetailValue(stringResource(R.string.ui_amount),mas(review.amount.text))
            DetailValue(stringResource(R.string.ui_network_fee),mas(review.fee.text));DetailValue(stringResource(R.string.ui_total),mas(review.total.text))
        }}
        if(review.credit.creationPossible)Text(stringResource(R.string.send_credit,review.credit.minimum.text,review.credit.maximum.text),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        SlideConfirmation(review.commitment,!observed.busy){confirm(review)}
    }else{
        val recipientLabel=stringResource(R.string.send_recipient)
        var recentOpen by remember{mutableStateOf(false)}
        MXPanel{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(stringResource(R.string.ui_recipient),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment=Alignment.CenterVertically){
                androidx.compose.foundation.text.BasicTextField(recipient,{recipient=it.take(120);edit()},enabled=true,singleLine=true,modifier=Modifier.weight(1f).heightIn(min=48.dp).semantics{contentDescription=recipientLabel},textStyle=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface),keyboardOptions=KeyboardOptions(autoCorrectEnabled=false),cursorBrush=androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),decorationBox={inner->Box(contentAlignment=Alignment.CenterStart){if(recipient.isEmpty())Text(stringResource(R.string.ui_address_hint),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=17.sp);inner()}})
                MXIconAction(MXGlyph.COPY,stringResource(R.string.send_paste)){recipient=clipboard.getText()?.text?.take(120).orEmpty();edit()}
                MXIconAction(MXGlyph.ACTIVITY,stringResource(R.string.recent_recipients)){recentOpen=!recentOpen}
            }
        }}
        if(recentOpen){
            val recent=observed.records.filter{it.state==TransferState.VERIFIED_SUCCESS&&it.signed.draft.source.identity==source?.identity}.asReversed().distinctBy{it.signed.draft.recipient}.take(5)
            if(recent.isEmpty())Text(stringResource(R.string.no_recent_recipients))
            recent.forEach{entry->TextButton(onClick={recipient=entry.signed.draft.recipient;recentOpen=false;edit()}){Text(shortPublic(entry.signed.draft.recipient))}}
        }
        MXPanel{Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(stringResource(R.string.ui_amount),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment=Alignment.CenterVertically){
                androidx.compose.foundation.text.BasicTextField(amount,{amount=normalizeAmountEdit(it);edit()},singleLine=true,modifier=Modifier.weight(1f).heightIn(min=56.dp).semantics{contentDescription="MAS"},textStyle=MaterialTheme.typography.headlineLarge.copy(color=MaterialTheme.colorScheme.onSurface),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,autoCorrectEnabled=false),cursorBrush=androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),decorationBox={inner->Box(contentAlignment=Alignment.CenterStart){if(amount.text.isEmpty())Text("0",fontSize=34.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);inner()}})
                Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.primaryContainer){Text("MAS",Modifier.padding(14.dp),color=MaterialTheme.colorScheme.primary)}
            }
        }}
        val snapshot=observed.preview?.takeIf{observed.previewRecipient==recipient.trim()}
        var now by remember{mutableLongStateOf(System.currentTimeMillis())}
        LaunchedEffect(Unit){while(true){now=System.currentTimeMillis();kotlinx.coroutines.delay(1000)}}
        val fresh=snapshot!=null&&runCatching{snapshot.validate(now)}.isSuccess
        val valid=fresh&&runCatching{ReviewedTransfer.make(source!!,SendInput.recipient(recipient),amount.text,snapshot!!,now)}.isSuccess
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text((snapshot?.takeIf{fresh}?.spendable?.text?:balance)?.let{stringResource(R.string.send_available,com.massivex.android.presentation.localizedDecimal(it,locale))}?:stringResource(if(balanceLoading||observed.busy)R.string.read_loading else R.string.read_unavailable),Modifier.weight(1f),fontSize=14.sp)
            TextButton(enabled=!observed.busy&&fresh,onClick={maximum(recipient)},shape=RoundedCornerShape(24.dp),colors=ButtonDefaults.textButtonColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){Text(stringResource(R.string.send_max))}
        }
        MXButton(stringResource(R.string.continue_action),!observed.busy&&valid){prepare(recipient,amount.text)}
    }
    if(observed.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    observed.error?.let{Text(stringResource(sendErrorString(it)),style=MaterialTheme.typography.bodySmall)}
}
internal enum class TransferDisplayState { SIGNED, PENDING, UNKNOWN, COMPLETED, FAILED }
internal fun TransferRecord.displayState():TransferDisplayState = when {
    state==TransferState.VERIFIED_SUCCESS&&resolution?.success==true->TransferDisplayState.COMPLETED
    state==TransferState.VERIFIED_FAILURE&&resolution?.success==false->TransferDisplayState.FAILED
    state==TransferState.PENDING->TransferDisplayState.PENDING
    state==TransferState.SIGNED->TransferDisplayState.SIGNED
    else->TransferDisplayState.UNKNOWN // A durable attempt alone cannot prove propagation/acknowledgment.
}
@Composable internal fun TransferStatus(record:TransferRecord){
    val locale=androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val display=record.displayState()
    val success=display==TransferDisplayState.COMPLETED
    val pending=display==TransferDisplayState.PENDING
    Column(Modifier.fillMaxWidth().padding(vertical=28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)){
        Surface(shape=RoundedCornerShape(48.dp),color=if(success)androidx.compose.ui.graphics.Color(0xFF164D37)else MaterialTheme.colorScheme.surfaceVariant){Box(Modifier.size(88.dp),contentAlignment=Alignment.Center){Text(if(success)"✓"else if(pending)"◷"else "!",fontSize=48.sp,color=if(success)androidx.compose.ui.graphics.Color(0xFF4BE59B)else MaterialTheme.colorScheme.onSurface)}}
        Text(stringResource(when{success->R.string.ui_completed;display==TransferDisplayState.FAILED->R.string.ui_failed;display==TransferDisplayState.SIGNED->R.string.send_signed;pending->R.string.ui_pending;else->R.string.ui_unknown}),fontSize=30.sp)
        Text(com.massivex.android.presentation.localizedDecimal(record.signed.draft.amount.text,locale)+" MAS",fontSize=22.sp)
        Text(shortPublic(record.signed.draft.recipient),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!record.state.terminal)Text(stringResource(R.string.send_pending),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(record.state.terminal)Text(stringResource(R.string.send_trust),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
/** A tap on the track cannot submit. Drag must start at the thumb and cross >=95% of travel. */
@Composable internal fun SlideConfirmation(identity:String,enabled:Boolean,onConfirm:()->Unit){
    val thumb=with(LocalDensity.current){72.dp.toPx()}
    var width by remember{mutableFloatStateOf(1f)}
    var offset by remember(identity,enabled){mutableFloatStateOf(0f)}
    var armed by remember(identity,enabled){mutableStateOf(false)}
    val label=stringResource(R.string.send_slide);val accessible=stringResource(R.string.send_accessible_confirm)
    Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer)
        .onSizeChanged{width=it.width.toFloat()}
        .semantics{contentDescription=label;if(!enabled)disabled() else customActions=listOf(CustomAccessibilityAction(accessible){onConfirm();true})}
        .pointerInput(identity,enabled,width){
            detectHorizontalDragGestures(onDragStart={armed=enabled&&it.x in 0f..thumb;offset=0f},onDragCancel={armed=false;offset=0f},
                onHorizontalDrag={change,delta->if(armed){change.consume();offset=(offset+delta).coerceIn(0f,(width-thumb).coerceAtLeast(1f))}},
                onDragEnd={if(armed&&offset>=(width-thumb).coerceAtLeast(1f)*.95f)onConfirm();armed=false;offset=0f})
        },contentAlignment=Alignment.Center){
        Text(label,modifier=Modifier.padding(start=36.dp))
        Box(Modifier.align(Alignment.CenterStart).offset{IntOffset(offset.roundToInt(),0)}.size(72.dp).padding(5.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary),contentAlignment=Alignment.Center){ReviewArrow()}
    }
}
internal fun sendErrorString(error:SendError):Int=when(error){
    SendError.WALLET->R.string.send_error_wallet;SendError.RECIPIENT->R.string.send_error_recipient;SendError.AMOUNT->R.string.send_error_amount
    SendError.FUNDS->R.string.send_error_funds;SendError.OVERFLOW->R.string.send_error_overflow;SendError.CREATION_MINIMUM->R.string.send_error_creation
    SendError.NETWORK->R.string.send_error_network;SendError.STALE,SendError.CHANGED->R.string.send_error_stale
    SendError.AUTH,SendError.SIGNATURE->R.string.send_error_auth;SendError.CANCELLED->R.string.send_error_cancelled
    SendError.JOURNAL->R.string.send_error_journal;SendError.CAPACITY->R.string.send_error_capacity;SendError.DUPLICATE->R.string.send_error_duplicate
    SendError.DISABLED->R.string.send_disabled;SendError.EVIDENCE->R.string.send_error_evidence;SendError.LOCKED->R.string.send_pending;SendError.BUSY->R.string.send_working
}

@Composable private fun ReviewAddress(label:String,value:String,copy:(String)->Unit){
    var expanded by remember(value){mutableStateOf(false)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),fontSize=14.sp);TextButton(onClick={expanded=true}){Text(shortPublic(value)+" ⌄",fontSize=13.sp)}}
    if(expanded)AlertDialog(onDismissRequest={expanded=false},title={Text(label)},text={PublicCopy(value,copy)},confirmButton={TextButton(onClick={expanded=false}){Text(stringResource(R.string.ui_done))}})
}

@Composable private fun ReviewArrow(){
    val color=MaterialTheme.colorScheme.onPrimary
    androidx.compose.foundation.Canvas(Modifier.size(32.dp)){
        val stroke=3.dp.toPx()
        val mid=size.height/2
        drawLine(color,androidx.compose.ui.geometry.Offset(size.width*.15f,mid),androidx.compose.ui.geometry.Offset(size.width*.85f,mid),stroke,androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color,androidx.compose.ui.geometry.Offset(size.width*.55f,size.height*.2f),androidx.compose.ui.geometry.Offset(size.width*.85f,mid),stroke,androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color,androidx.compose.ui.geometry.Offset(size.width*.85f,mid),androidx.compose.ui.geometry.Offset(size.width*.55f,size.height*.8f),stroke,androidx.compose.ui.graphics.StrokeCap.Round)
    }
}
