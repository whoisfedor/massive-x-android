package com.massivex.android

import android.content.Context
import com.massivex.android.presentation.*
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.massivex.android.wallet.*
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var presentation: PresentationStore
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(PresentationStore.localized(newBase)) }
    private lateinit var model: WalletModel
    private lateinit var readModel: ReadModel
    private lateinit var sendModel: SendModel
    private val secretClipboard by lazy { SecretClipboard(this) }
    internal val capturePolicy by lazy { CapturePolicy(window) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // Start protected until the first public frame is committed.
        enableEdgeToEdge()
        presentation = PresentationStore(this)
        model = ViewModelProvider(this)[WalletModel::class.java]
        readModel = ViewModelProvider(this)[ReadModel::class.java]
        sendModel = ViewModelProvider(this)[SendModel::class.java]
        val secrets = AndroidSecrets(applicationContext)
        val auth = AndroidAuthentication(this, secrets)
        val repository = WalletRepository(AtomicCatalog(applicationContext), secrets, Randomness { ByteArray(32).also { SecureRandom().nextBytes(it) } }, auth, { index -> if (index == 1) getString(R.string.default_wallet) else getString(R.string.numbered_wallet, index) }, sendModel.ledger)
        model.attach(repository, auth)
        sendModel.attach(repository,auth)
        setContent { WalletApp(model, readModel.reads, sendModel, presentation, { tag -> presentation.language(tag); if(android.os.Build.VERSION.SDK_INT<33)recreate() }, { value -> openExternal(this,value) }, ::copyAddress, ::copySecret, ::clearOwnedClip) }
    }
    private fun copyAddress(address: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.public_data), address))
    }
    internal fun copySecret(key: String) = secretClipboard.copy(key)
    internal fun clearOwnedClip() = secretClipboard.clear()
    internal fun hasOwnedClip() = secretClipboard.ownsCurrent()
    override fun onStart() {
        super.onStart()
        if(::presentation.isInitialized)presentation.refresh()
        if (::readModel.isInitialized) {
            val wallet = model.state.value.catalog.selected
            readModel.reads.select(wallet?.id, wallet?.address); readModel.reads.resume()
            sendModel.select(wallet);sendModel.resume()
        }
    }
    override fun onPause() { if (::model.isInitialized) model.obscure(); super.onPause() }
    override fun onStop() { if (::sendModel.isInitialized) sendModel.background(); if (::readModel.isInitialized) readModel.reads.stop(); if (::model.isInitialized) model.background(); super.onStop() }
    override fun onDestroy() { if (::model.isInitialized) model.background(); super.onDestroy() }
}

@Composable
private fun WalletApp(model: WalletModel, reads: com.massivex.android.network.ReadCoordinator, send: SendModel, presentation: PresentationStore, language: (String)->Unit, open: (String)->Unit, copyAddress: (String) -> Unit, copySecret: (String) -> Unit, clearClip: () -> Unit) {
    val context=LocalContext.current
    val preferences by presentation.state.collectAsStateWithLifecycle()
    val ui by model.state.collectAsStateWithLifecycle()
    val publicReads by reads.state.collectAsStateWithLifecycle()
    val transfer by send.coordinator.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.catalog.selected?.id, ui.catalog.selected?.address,ui.catalog.wallets) {
        reads.select(ui.catalog.selected?.id,ui.catalog.selected?.address);send.select(ui.catalog.selected)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){com.massivex.android.notifications.BalanceNotifications.sync(context)}
    }
    LaunchedEffect(ui.screen){if(ui.screen==Screen.SEND)reads.refresh()}
    WalletTheme(preferences) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                // DocumentsUI stops this Activity. Keep its result registration stable; the
                // existing pause/stop privacy handler still clears every secret field.
                key(ui.screen, if(ui.screen==Screen.YAML)0 else ui.privacyEpoch) {
                    WalletScreen(ui,model,reads,publicReads,send,transfer,preferences,presentation,language,open,copyAddress,copySecret,clearClip)
                }
                if(ui.screen in setOf(Screen.HOME,Screen.ACTIVITY,Screen.SETTINGS))Box(Modifier.align(Alignment.BottomCenter)){MXTabs(ui.screen,ui.catalog.selected!=null,model::navigate)}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletScreen(ui: WalletUi, model: WalletModel, reads: com.massivex.android.network.ReadCoordinator, publicReads: com.massivex.android.network.ReadUi, send: SendModel, transfer: com.massivex.android.transfer.SendUi, preferences: Presentation, presentation: PresentationStore, language: (String)->Unit, open: (String)->Unit, copyAddress: (String) -> Unit, copySecret: (String) -> Unit, clearClip: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var manualYaml by remember{mutableStateOf(false)}
    var revealed by remember { mutableStateOf<String?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val wallet = ui.catalog.selected
    val formContext=LocalContext.current
    val yamlPicker=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null)runCatching{
            formContext.contentResolver.openInputStream(uri)?.use{stream->
                val bytes=java.io.ByteArrayOutputStream().also{out->val buffer=ByteArray(1024);while(true){val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=16384);out.write(buffer,0,n)}}.toByteArray()
                try{(formContext as MainActivity).capturePolicy.sensitive(true);input=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()}finally{bytes.fill(0)}
            }
        }.onFailure{input=""}
    }
    val scroll = rememberScrollState()
    val pageScope=rememberCoroutineScope()
    LaunchedEffect(ui.error) { if (ui.error != null) scroll.animateScrollTo(0) }
    val mask = { input = ""; password = ""; revealed = null }
    val activity = formContext as MainActivity
    // Apply protection synchronously before composing any secret-bearing subtree.
    activity.capturePolicy.sensitive((ui.screen == Screen.PRIVATE && input.isNotEmpty()) || (ui.screen == Screen.YAML && (password.isNotEmpty() || input.isNotEmpty())) || revealed != null)
    var ownedCopy by remember { mutableStateOf(activity.hasOwnedClip()) }
    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) ownedCopy = activity.hasOwnedClip()
        }
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        val listener = ClipboardManager.OnPrimaryClipChangedListener { ownedCopy = activity.hasOwnedClip() }
        activity.lifecycle.addObserver(observer)
        clipboard.addPrimaryClipChangedListener(listener)
        onDispose { activity.lifecycle.removeObserver(observer); clipboard.removePrimaryClipChangedListener(listener) }
    }
    DisposableEffect(Unit) {
        model.privacyHandler(mask)
        onDispose { mask(); model.privacyHandler(null) }
    }
    LaunchedEffect(revealed) { if (revealed != null) { kotlinx.coroutines.delay(30000); revealed = null } }
    BackHandler(ui.screen != Screen.HOME && !ui.busy) { mask(); if (ui.screen==Screen.SEND) send.edit(); model.navigate(Screen.HOME) }
    Column(Modifier.safeDrawingPadding().imePadding().padding(bottom=if(ui.screen in setOf(Screen.HOME,Screen.ACTIVITY,Screen.SETTINGS))72.dp else 0.dp).verticalScroll(scroll).padding(horizontal=MX.gutter,vertical=12.dp), verticalArrangement = Arrangement.spacedBy(if(ui.screen in setOf(Screen.HOME,Screen.ACTIVITY))0.dp else MX.sectionGap)) {
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.error?.let { Text(stringResource(errorString(it)), color = MaterialTheme.colorScheme.error) }
        if (ui.blocked) Text(stringResource(R.string.blocked))
        if (ui.screen !in setOf(Screen.HOME,Screen.ACTIVITY,Screen.SETTINGS)) {
            val formSheet=ui.screen in setOf(Screen.CREATE,Screen.PRIVATE,Screen.YAML,Screen.WATCH)
            if(formSheet)Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){HorizontalDivider(Modifier.width(36.dp),thickness=4.dp)}
            Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=16.dp),verticalAlignment=Alignment.CenterVertically){
                val closePage={mask();if(ui.screen==Screen.SEND)send.edit();model.navigate(Screen.HOME)}
                if(ui.screen==Screen.SEND&&transfer.review!=null)MXIconAction(MXGlyph.BACK,stringResource(R.string.send_edit),!transfer.busy){send.edit()}
                else MXIconAction(MXGlyph.BACK,stringResource(R.string.back),!ui.busy,closePage)
                Text(stringResource(if(ui.screen==Screen.SEND&&transfer.review!=null)R.string.review_transfer else title(ui.screen)),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(48.dp))
            }
            if(formSheet)Spacer(Modifier.height(24.dp))
        }
        fun submit(destination: Screen = Screen.HOME, block: suspend (WalletRepository) -> Catalog?) { model.run(destination, block) }
        when (ui.screen) {
            Screen.HOME -> {
                if(wallet==null){
                    MXHeading(stringResource(R.string.ui_wallet))
                    Text(stringResource(R.string.empty_body),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    MXButton(stringResource(R.string.add_wallet),!ui.blocked&&!ui.busy){model.navigate(Screen.ADD)}
                }else{
                    ReadSummary(wallet,publicReads,reads,{destination->if(destination==Screen.SEND)send.edit();model.navigate(destination)},copyAddress,preferences.balanceHidden,presentation::hideBalance){choosing=true}
                    RecentActivity(publicReads.forWallet(wallet.id,wallet.address),reads,model::navigate)
                }
            }
            Screen.ADD -> {
                ActionButton(R.string.create, !ui.busy) { model.navigate(Screen.CREATE) }
                ActionButton(R.string.import_private, !ui.busy) { model.navigate(Screen.PRIVATE) }
                ActionButton(R.string.import_yaml, !ui.busy) { model.navigate(Screen.YAML) }
                ActionButton(R.string.add_watch, !ui.busy) { model.navigate(Screen.WATCH) }
            }
            Screen.CREATE, Screen.PRIVATE, Screen.YAML, Screen.WATCH -> {
                if(ui.screen in setOf(Screen.CREATE,Screen.YAML)) MXPanel { Text(stringResource(R.string.ui03_transfer_note),Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
                MXPanel { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(ui.screen==Screen.YAML){
                        MXRow(stringResource(R.string.ui_choose_file),MXGlyph.PLUS){manualYaml=false;yamlPicker.launch(arrayOf("application/octet-stream","application/x-yaml","text/*"))}
                        if(input.isNotEmpty()&&!manualYaml)Text(stringResource(R.string.ui_file_selected),style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={manualYaml=!manualYaml}){Text(stringResource(R.string.ui_paste_yaml))}
                    }
                    Entry(name, { name = it.take(40) }, R.string.optional_name, enabled = !ui.busy)
                    HorizontalDivider()
                    if(ui.screen==Screen.YAML){
                        if(manualYaml)Entry(input,{activity.capturePolicy.sensitive(true);input=it.take(16384)},R.string.yaml,multiline=true,enabled=!ui.busy)
                        Entry(password,{activity.capturePolicy.sensitive(true);password=it.take(1024)},R.string.password,secret=true,enabled=!ui.busy)
                    }else if(ui.screen!=Screen.CREATE)Entry(input,{if(ui.screen==Screen.PRIVATE)activity.capturePolicy.sensitive(true);input=it.take(120)},if(ui.screen==Screen.PRIVATE)R.string.private_key else R.string.address,secret=ui.screen==Screen.PRIVATE,enabled=!ui.busy)
                    Text(stringResource(when(ui.screen){Screen.CREATE->R.string.ui_no_mnemonic;Screen.PRIVATE->R.string.ui_private_note;Screen.YAML->R.string.ui_yaml_note;else->R.string.receive_watch}),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                    ActionButton(if(ui.screen==Screen.CREATE)R.string.create else R.string.add_wallet, !ui.busy && !ui.blocked) {
                        val entered = input; val pass = password; input = ""; password = ""
                        submit { repo -> when (ui.screen) {
                            Screen.CREATE -> repo.create(name)
                            Screen.PRIVATE -> repo.importPrivate(entered, name)
                            Screen.YAML -> { val bytes = entered.toByteArray(); try { repo.importYaml(bytes, pass, name) } finally { bytes.fill(0) } }
                            else -> repo.addWatch(entered, name)
                        } }
                    }
                } }
            }
            Screen.BACKUP -> if (wallet != null && !wallet.watchOnly) {
                val backupPrefs=formContext.getSharedPreferences("backup-ack-v1",Context.MODE_PRIVATE)
                var savedBackup by remember(wallet.id){mutableStateOf(backupPrefs.getBoolean(wallet.id,false))}
                BackupPanel(savedBackup,{savedBackup=true;backupPrefs.edit().putBoolean(wallet.id,true).apply()},revealed,ownedCopy,ui.busy,
                    reveal={if(revealed!=null)revealed=null else submit(Screen.BACKUP){it.backup(wallet.id,Purpose.REVEAL){value->activity.capturePolicy.sensitive(true);revealed=value};null}},
                    copy={revealed=null;submit(Screen.BACKUP){it.backup(wallet.id,Purpose.COPY){value->copySecret(value);ownedCopy=activity.hasOwnedClip()};null}},
                    clear={clearClip();ownedCopy=activity.hasOwnedClip()})
            }
            Screen.RENAME -> if (wallet != null) {
                Entry(name, { name = it.take(40) }, R.string.wallet_name, enabled = !ui.busy)
                ActionButton(R.string.save, name.isNotBlank() && !ui.busy) { submit { it.rename(wallet.id, name) } }
            }
            Screen.REMOVE -> if (wallet != null) {
                Text(wallet.name)
                Text(wallet.address)
                Text(stringResource(R.string.remove_warning))
                Consent(acknowledged, { acknowledged = it }, if (wallet.watchOnly) R.string.watch_remove_consent else R.string.remove_consent)
                ActionButton(R.string.remove_selected, acknowledged && !ui.busy) { submit { it.remove(wallet.id, acknowledged) } }
            }
            Screen.RECEIVE -> if (wallet != null) ReceiveScreen(wallet.address, wallet.watchOnly, copyAddress)
            Screen.ACTIVITY -> if (wallet != null) ActivityScreen(publicReads.forWallet(wallet.id, wallet.address), reads, model::navigate)
            Screen.OPERATION -> if (wallet != null) OperationScreen(publicReads.forWallet(wallet.id, wallet.address), copyAddress)
            Screen.CURRENCY -> CurrencyScreen(publicReads, reads)
            Screen.SEND -> if (wallet != null && !wallet.watchOnly) SendScreen(wallet,transfer,send::prepare,send::maximum,send::preview,send::edit,send::confirm,publicReads.balance?.takeIf{it.address==wallet.address&&!publicReads.stale}?.confirmed?.text,copyAddress,{model.navigate(Screen.HOME)},{send.resume()},balanceLoading=publicReads.balanceLoading)
            Screen.SETTINGS -> SettingsScreen(preferences,presentation,language,open,{model.navigate(Screen.CURRENCY)},publicReads.currency,scrollTop={pageScope.launch{scroll.scrollTo(0)}}){
                WalletManagement(ui,model,copyAddress)
            }
        }
    }
    if(choosing){
        ModalBottomSheet(onDismissRequest={choosing=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),dragHandle=null,containerColor=if(MaterialTheme.colorScheme.background.luminance()<.5f)Color(0xFF080A1A)else MaterialTheme.colorScheme.background,shape=androidx.compose.foundation.shape.RoundedCornerShape(topStart=24.dp,topEnd=24.dp)){
            Column(Modifier.fillMaxWidth().heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.9f).dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Spacer(Modifier.width(48.dp));Text(stringResource(R.string.choose_wallet),Modifier.weight(1f),textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.titleMedium);MXIconAction(MXGlyph.CLOSE,stringResource(R.string.ui_done)){choosing=false}}
                MXPanel(Modifier.weight(1f,fill=false)){Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())){
                    ui.catalog.wallets.forEachIndexed{index,item->
                        if(index>0)HorizontalDivider(Modifier.padding(start=64.dp),color=MaterialTheme.colorScheme.onSurface.copy(alpha=.07f))
                        Row(Modifier.fillMaxWidth().clickable(enabled=!ui.busy&&!ui.blocked){choosing=false;submitSelection(model,item.id)}.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            Surface(shape=androidx.compose.foundation.shape.CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(Modifier.size(44.dp),contentAlignment=Alignment.Center){MXIcon(if(item.watchOnly)MXGlyph.EYE else MXGlyph.WALLET)}}
                            Column(Modifier.weight(1f)){Text(item.name,style=MaterialTheme.typography.titleMedium);Text(item.address.take(7)+"…"+item.address.takeLast(5),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);if(item.watchOnly)Text(stringResource(R.string.watch_label),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                            if(item.id==ui.catalog.selectedId){val selectedLabel=stringResource(R.string.wallet_selected);Box(Modifier.semantics{contentDescription=selectedLabel}){MXIcon(MXGlyph.CHECK)}}
                        }
                    }
                }}
                MXButton(stringResource(R.string.add_wallet),!ui.busy&&!ui.blocked,icon=MXGlyph.PLUS){choosing=false;model.navigate(Screen.ADD)}
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
@Composable private fun WalletManagement(ui:WalletUi,model:WalletModel,copyAddress:(String)->Unit){
    val wallet=ui.catalog.selected
    MXPanel{Column{
        Column(Modifier.heightIn(max=240.dp).verticalScroll(rememberScrollState())){ui.catalog.wallets.forEach{item->
            Row(Modifier.fillMaxWidth().clickable(enabled=!ui.busy&&!ui.blocked){submitSelection(model,item.id)}.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                MXIcon(if(item.watchOnly)MXGlyph.EYE else MXGlyph.WALLET)
                Column(Modifier.weight(1f)){Text(item.name,style=MaterialTheme.typography.titleMedium);PublicCopy(item.address,copyAddress,true);if(item.watchOnly)Text(stringResource(R.string.watch_label),style=MaterialTheme.typography.labelSmall);if(item.removing)Text(stringResource(R.string.removal_pending))}
                if(item.id==ui.catalog.selectedId)MXIcon(MXGlyph.CHECK)
            };HorizontalDivider()
        }
        }
        MXRow(stringResource(R.string.add_wallet),MXGlyph.PLUS,enabled=!ui.busy&&!ui.blocked){model.navigate(Screen.ADD)}
    }}
    if(wallet!=null){
        Text(stringResource(R.string.ui_wallet).uppercase(),Modifier.padding(start=16.dp,top=24.dp,bottom=8.dp),style=MaterialTheme.typography.labelSmall)
        if(!wallet.watchOnly&&!wallet.removing)MXRow(stringResource(R.string.backup),MXGlyph.WALLET,onClick={model.navigate(Screen.BACKUP)})
        MXRow(stringResource(R.string.rename),MXGlyph.WALLET,onClick={model.navigate(Screen.RENAME)})
        MXRow(stringResource(R.string.remove),MXGlyph.TRASH,onClick={model.navigate(Screen.REMOVE)})
    }
}


private fun submitSelection(model:WalletModel,id:String){model.run{it.select(id)}}
@Composable private fun ActionButton(label: Int, enabled: Boolean, action: () -> Unit) {
    MXButton(stringResource(label),enabled,action=action)
}
@Composable private fun Consent(value: Boolean, change: (Boolean) -> Unit, label: Int) {
    Row { Checkbox(value, change); Text(stringResource(label), Modifier.padding(top = 12.dp)) }
}
@Composable private fun Entry(value: String, change: (String) -> Unit, label: Int, secret: Boolean = false, multiline: Boolean = false, enabled: Boolean = true) {
    TextField(value, change, colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surface,unfocusedContainerColor=MaterialTheme.colorScheme.surface,disabledContainerColor=MaterialTheme.colorScheme.surface,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent), label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth(), shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp), enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 4 else 1, maxLines = if (multiline) 6 else 1,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { defaultKeyboardAction(ImeAction.Done) }))
}
private fun title(screen: Screen): Int = when (screen) {
    Screen.HOME -> R.string.wallets; Screen.ADD -> R.string.add_wallet; Screen.CREATE -> R.string.ui03_new_wallet
    Screen.PRIVATE -> R.string.import_private; Screen.YAML -> R.string.import_yaml; Screen.WATCH -> R.string.add_watch
    Screen.BACKUP -> R.string.backup; Screen.RENAME -> R.string.rename; Screen.REMOVE -> R.string.remove; Screen.SETTINGS -> R.string.settings; Screen.RECEIVE -> R.string.receive; Screen.ACTIVITY -> R.string.activity; Screen.OPERATION -> R.string.operation_details; Screen.CURRENCY -> R.string.currency_title; Screen.SEND -> R.string.send
}
internal fun errorString(error: WalletError): Int = when (error) {
    WalletError.TRANSFER_LOCKED -> R.string.transfer_locked
    WalletError.INVALID_KEY -> R.string.error_key; WalletError.INVALID_YAML -> R.string.error_yaml
    WalletError.INVALID_ADDRESS -> R.string.error_address; WalletError.DUPLICATE -> R.string.error_duplicate
    WalletError.LOCK_REQUIRED -> R.string.error_lock; WalletError.UNSUPPORTED -> R.string.error_unsupported
    WalletError.MISSING_KEY -> R.string.error_missing; WalletError.INVALIDATED -> R.string.error_invalidated
    WalletError.CORRUPT -> R.string.error_corrupt; WalletError.VERSION -> R.string.error_version
    WalletError.CANCELLED -> R.string.error_cancelled; WalletError.EXPIRED -> R.string.error_expired
    WalletError.AUTH -> R.string.error_auth; WalletError.WATCH_ONLY -> R.string.error_watch
    WalletError.BUSY, WalletError.CHANGED -> R.string.error_changed; WalletError.ORPHAN -> R.string.error_orphan
    WalletError.INCOMPLETE -> R.string.error_incomplete; WalletError.STORAGE -> R.string.error_storage
}
