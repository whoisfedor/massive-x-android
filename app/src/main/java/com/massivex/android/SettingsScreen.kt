package com.massivex.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.massivex.android.presentation.*
import kotlinx.coroutines.launch

@Composable internal fun SettingsScreen(p:Presentation,store:PresentationStore,language:(String)->Unit,open:(String)->Unit,currency:()->Unit,currencyValue:String="",scrollTop:()->Unit={},management:@Composable ()->Unit={}){
    var detail by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(detail){scrollTop()}
    val context=androidx.compose.ui.platform.LocalContext.current
    val permission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){granted->store.alerts(granted)}
    var menu by remember{mutableStateOf<String?>(null)}
    var problem by remember{mutableStateOf(false)}
    BackHandler(detail!=null){detail=null}
    if(detail==null){
        MXHeading(stringResource(R.string.settings))
        MXPanel{Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
            Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.primaryContainer){Image(painterResource(iconImage(p.icon)),null,Modifier.size(48.dp))}
            Column{Text(stringResource(R.string.app_name),fontWeight=FontWeight.SemiBold,fontSize=18.sp);Text(androidVersion(),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }}
        SectionLabel(R.string.preferences)
        MXPanel { Column {
        MXRow(stringResource(R.string.ui_manage),MXGlyph.WALLET){detail="wallets"}
        MXRow(stringResource(R.string.currency_title),MXGlyph.CURRENCY,currencyValue,onClick=currency)
        MXRow(stringResource(R.string.accent_color),MXGlyph.PALETTE,stringResource(accentLabel(p.accent))){menu="accent"}
        val languages=listOf("" to R.string.system_language,"en" to R.string.language_en,"fr" to R.string.language_fr,"ru" to R.string.language_ru,"es" to R.string.language_es,"zh-Hans" to R.string.language_zh,"de" to R.string.language_de)
        MXRow(stringResource(R.string.language),MXGlyph.LANGUAGE,stringResource(languages.firstOrNull{it.first==p.language}?.second?:R.string.system_language)){detail="language"}
        MXRow(stringResource(R.string.balance_alerts),MXGlyph.BELL){detail="alerts"}
        MXRow(stringResource(R.string.appearance),MXGlyph.APPEARANCE,stringResource(appearanceLabel(p.appearance))){menu="appearance"}
        }}
        SectionLabel(R.string.ui_app_section)
        MXPanel { Column {
        MXRow(stringResource(R.string.buy_mas),MXGlyph.CURRENCY){detail="buy"}
        MXRow(stringResource(R.string.ecosystem),MXGlyph.GRID){detail="ecosystem"}
        MXRow(stringResource(R.string.ui_connection),MXGlyph.GLOBE){detail="connection"}
        MXRow(stringResource(R.string.about_roadmap),MXGlyph.INFO){detail="about"}
        MXRow(stringResource(R.string.privacy),MXGlyph.SHARE){open(ExternalDestinations.fixed.getValue("privacy"))}
        MXRow(stringResource(R.string.support),MXGlyph.SHARE){open(ExternalDestinations.fixed.getValue("support"))}
        MXRow(stringResource(R.string.app_icon),MXGlyph.PALETTE){detail="icons"}
        }}
        MXPanel{Row(Modifier.padding(16.dp).fillMaxWidth()){Text(stringResource(R.string.ui_version),Modifier.weight(1f));Text(androidVersion(),color=MaterialTheme.colorScheme.onSurfaceVariant)}}
    }else{
        val title=when(detail){"wallets"->R.string.ui_manage;"accent"->R.string.accent_color;"language"->R.string.language;"alerts"->R.string.balance_alerts;"appearance"->R.string.appearance;"buy"->R.string.buy_mas;"ecosystem"->R.string.ecosystem;"connection"->R.string.ui_connection;"icons"->R.string.app_icon;else->R.string.about_roadmap}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(stringResource(title),Modifier.weight(1f),fontSize=24.sp,fontWeight=FontWeight.Bold);MXIconAction(MXGlyph.CLOSE,stringResource(R.string.ui_done)){detail=null}}
        when(detail){
            "wallets"->management()
            "language"->listOf("" to R.string.system_language,"en" to R.string.language_en,"fr" to R.string.language_fr,"ru" to R.string.language_ru,"es" to R.string.language_es,"zh-Hans" to R.string.language_zh,"de" to R.string.language_de).forEach{(value,label)->MXRow(stringResource(label)+(if(value==p.language)" ✓"else""),MXGlyph.LANGUAGE){language(value)}}
            "alerts"->{MXPanel{Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Switch(p.balanceAlerts,{enable->if(enable&&android.os.Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)else store.alerts(enable)});Text(stringResource(R.string.balance_alerts),Modifier.weight(1f))}};Text(stringResource(R.string.alerts_scope),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            "icons"->{LauncherIcon.entries.chunked(2).forEach{choices->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){choices.forEach{icon->TextButton(modifier=Modifier.weight(1f),onClick={problem=runCatching{store.icon(icon)}.isFailure}){Column(horizontalAlignment=Alignment.CenterHorizontally){Image(painterResource(iconImage(icon)),null,Modifier.size(64.dp));Text(icon.code+" · "+stringResource(iconLabel(icon))+(if(p.icon==icon)" ✓"else""))}}}}};if(problem)Text(stringResource(R.string.icon_failed))}
            "buy"->{MXPanel{Column{LinkRow(R.string.link_bitget,"bitget",open);Text(stringResource(R.string.ui_spot_trading),Modifier.padding(start=54.dp,bottom=14.dp),fontSize=12.sp);HorizontalDivider();LinkRow(R.string.link_mexc,"mexc",open);Text(stringResource(R.string.ui_spot_trading),Modifier.padding(start=54.dp,bottom=14.dp),fontSize=12.sp)}};Text(stringResource(R.string.ui_exchange_note),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            "ecosystem"->{AppHeader();MXPanel{Column{listOf(R.string.link_explorer to "explorer",R.string.link_dusa to "dusa",R.string.link_bridge to "bridge",R.string.link_eaglefi to "eaglefi",R.string.link_massa to "massa").forEach{LinkRow(it.first,it.second,open)}}};Text(stringResource(R.string.ui_external_note),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            "connection"->{
                var checking by remember{mutableStateOf(false)}
                var available by remember{mutableStateOf<Boolean?>(null)}
                val scope=rememberCoroutineScope()
                MXPanel{Column(Modifier.padding(16.dp)){DetailValue(stringResource(R.string.ui_only_network),"Massa Mainnet");HorizontalDivider();Text("https://mainnet.massa.net/api/v2",Modifier.padding(vertical=16.dp),fontSize=13.sp)}}
                MXRow(stringResource(R.string.ui_check_connection),MXGlyph.REFRESH,if(checking)stringResource(R.string.read_loading)else available?.let{stringResource(if(it)R.string.ui_connected else R.string.read_unavailable)},enabled=!checking){checking=true;scope.launch{available=runCatching{com.massivex.android.transfer.MainnetSendReads().checkContext()}.isSuccess;checking=false}}
            }
            else->AboutRoadmap(open)

        }
    }
    if(menu!=null) AlertDialog(onDismissRequest={menu=null},title={Text(stringResource(if(menu=="accent")R.string.accent_color else R.string.appearance))},text={Column{
        if(menu=="accent")Accent.entries.forEach{a->MXRow(stringResource(accentLabel(a))+(if(p.accent==a)" ✓"else""),MXGlyph.PALETTE){store.accent(a);menu=null}}
        else Appearance.entries.forEach{a->MXRow(stringResource(appearanceLabel(a))+(if(p.appearance==a)" ✓"else""),MXGlyph.APPEARANCE){store.appearance(a);menu=null}}
    }},confirmButton={TextButton(onClick={menu=null}){Text(stringResource(R.string.ui_done))}})

}
@Composable private fun SectionLabel(label:Int){Text(stringResource(label).uppercase(),Modifier.padding(start=16.dp,top=22.dp,bottom=4.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable private fun LinkRow(label:Int,key:String,open:(String)->Unit){MXRow(stringResource(label),MXGlyph.SHARE){open(ExternalDestinations.fixed.getValue(key))}}
@Composable private fun androidVersion():String{
    val context=androidx.compose.ui.platform.LocalContext.current
    val info=remember{context.packageManager.getPackageInfo(context.packageName,0)}
    val code=if(android.os.Build.VERSION.SDK_INT>=28)info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    return "Android ${info.versionName} ($code)"
}
internal fun appearanceLabel(a:Appearance)=when(a){Appearance.DARK->R.string.appearance_dark;Appearance.LIGHT->R.string.appearance_light;Appearance.SYSTEM->R.string.appearance_system}
internal fun accentLabel(a:Accent)=when(a){Accent.ORANGE->R.string.accent_orange;Accent.MIDNIGHT->R.string.accent_midnight;Accent.CRIMSON->R.string.accent_crimson}
internal fun iconLabel(i:LauncherIcon)=when(i){LauncherIcon.CLASSIC->R.string.icon_classic;LauncherIcon.MIDNIGHT->R.string.icon_midnight;LauncherIcon.AUREUM->R.string.icon_aureum;LauncherIcon.CRIMSON->R.string.icon_crimson}
internal fun iconImage(i:LauncherIcon)=when(i){LauncherIcon.CLASSIC->R.drawable.icon_001;LauncherIcon.MIDNIGHT->R.drawable.icon_002;LauncherIcon.AUREUM->R.drawable.icon_003;LauncherIcon.CRIMSON->R.drawable.icon_004}

@Composable private fun AppHeader(){MXPanel{Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){Image(painterResource(R.drawable.icon_001),null,Modifier.size(56.dp));Column{Text(stringResource(R.string.app_name),fontWeight=FontWeight.Bold,fontSize=20.sp);Text("Massa",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}}

@Composable private fun AboutRoadmap(open:(String)->Unit){
    var expanded by remember{mutableStateOf<Int?>(null)}
    MXPanel{Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text(stringResource(R.string.ui03_about_title),fontSize=25.sp,fontWeight=FontWeight.Bold)
        Text(stringResource(R.string.about_android),color=MaterialTheme.colorScheme.onSurfaceVariant)
        MXRow("massive-x.com ↗",null,accent=true,disclosure=false){open(ExternalDestinations.fixed.getValue("website"))}
        MXRow(stringResource(R.string.ui03_about_app),MXGlyph.INFO){expanded=-1}
        LinkRow(R.string.privacy,"privacy",open)
    }}
    Text(stringResource(R.string.ui03_roadmap),fontSize=25.sp,fontWeight=FontWeight.Bold)
    val titles=listOf(R.string.ui03_owned,R.string.ui03_protected,R.string.ui03_payments,R.string.ui03_balances,R.string.ui03_estimates,R.string.ui03_personalize,R.string.ui03_next,R.string.ui_planned,R.string.ui03_long_term)
    titles.forEachIndexed{index,label->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(14.dp)){
            Column(Modifier.padding(top=16.dp),horizontalAlignment=Alignment.CenterHorizontally){Surface(shape=androidx.compose.foundation.shape.CircleShape,color=if(index<6)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface){Box(Modifier.size(28.dp),contentAlignment=Alignment.Center){MXIcon(if(index<6)MXGlyph.CHECK else MXGlyph.INFO,Modifier.size(20.dp),if(index<6)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)}};Box(Modifier.width(2.dp).height(112.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha=.15f)))}
            MXPanel(Modifier.weight(1f)){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(stringResource(if(index<6)R.string.ui03_shipped else R.string.ui03_subject),fontSize=12.sp,color=if(index<6)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(label),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                MXRow(stringResource(R.string.ui03_details),null,accent=true){expanded=index}
            }}
        }
    }
    Text(stringResource(R.string.ui03_no_dates),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    expanded?.let{index->AlertDialog(onDismissRequest={expanded=null},title={Text(if(index<0)stringResource(R.string.ui03_about_app)else stringResource(titles[index]))},text={Text(when{index<0->androidVersion()+"\n"+stringResource(R.string.independent_wallet);index==1->stringResource(R.string.ui_storage_limits_note);index==2->stringResource(R.string.send_trust);index==3->stringResource(R.string.alerts_scope);index>=6->stringResource(R.string.ui_planned_features);else->stringResource(R.string.ui_available_features)})},confirmButton={TextButton(onClick={expanded=null}){Text(stringResource(R.string.ui_done))}})}
}
