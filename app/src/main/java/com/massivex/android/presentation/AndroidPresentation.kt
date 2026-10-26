package com.massivex.android.presentation

import android.app.LocaleManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PresentationStore(private val context:Context) {
    private val prefs=context.getSharedPreferences("presentation-v1",Context.MODE_PRIVATE)
    private fun read()=Presentation(runCatching{Appearance.valueOf(prefs.getString("appearance","DARK")!!)}.getOrDefault(Appearance.DARK),
        runCatching{Accent.valueOf(prefs.getString("accent","CRIMSON")!!)}.getOrDefault(Accent.CRIMSON),
        runCatching{LauncherIcon.valueOf(prefs.getString("icon","CLASSIC")!!)}.getOrDefault(LauncherIcon.CLASSIC),currentLanguage(context),com.massivex.android.notifications.BalanceNotifications.enabled(context),prefs.getBoolean("hidden",false))
    private val mutable=MutableStateFlow(read());val state=mutable.asStateFlow()
    fun appearance(value:Appearance){prefs.edit().putString("appearance",value.name).apply();mutable.value=read()}
    fun accent(value:Accent){prefs.edit().putString("accent",value.name).apply();mutable.value=read()}
    fun hideBalance(value:Boolean){prefs.edit().putBoolean("hidden",value).apply();mutable.value=read()}
    fun alerts(value:Boolean){com.massivex.android.notifications.BalanceNotifications.setEnabled(context,value);mutable.value=read()}
    fun refresh(){mutable.value=read();repairIcon()}
    fun language(value:String){require(value.isEmpty()||value in PresentationPolicy.languages)
        if(Build.VERSION.SDK_INT>=33)context.getSystemService(LocaleManager::class.java).applicationLocales=LocaleList.forLanguageTags(value)
        else prefs.edit().putString("language",value).commit()
        mutable.value=read()
    }
    fun icon(value:LauncherIcon){applyIcon(value);prefs.edit().putString("icon",value.name).commit();mutable.value=read()}
    private fun component(icon:LauncherIcon)=ComponentName(context.packageName,"com.massivex.android.launcher.${icon.alias}")
    private fun enabled(icon:LauncherIcon):Boolean=when(context.packageManager.getComponentEnabledSetting(component(icon))){
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED->true;PackageManager.COMPONENT_ENABLED_STATE_DEFAULT->icon==LauncherIcon.CLASSIC;else->false
    }
    private fun applyIcon(target:LauncherIcon){
        val pm=context.packageManager
        if(Build.VERSION.SDK_INT>=33){pm.setComponentEnabledSettings(LauncherIcon.entries.map{PackageManager.ComponentEnabledSetting(component(it),
            if(it==target)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)})}
        else {pm.setComponentEnabledSetting(component(target),PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
            LauncherIcon.entries.filter{it!=target}.forEach{pm.setComponentEnabledSetting(component(it),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)}}
        check(LauncherIcon.entries.filter(::enabled)==listOf(target))
    }
    private fun repairIcon(){val chosen=mutable.value.icon;if(LauncherIcon.entries.filter(::enabled)!=listOf(chosen))runCatching{applyIcon(chosen)}}
    companion object {
        fun currentLanguage(context:Context):String = if(Build.VERSION.SDK_INT>=33)context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',')
            else context.getSharedPreferences("presentation-v1",Context.MODE_PRIVATE).getString("language","").orEmpty()
        fun localized(context:Context):Context {
            if(Build.VERSION.SDK_INT>=33)return context
            val language=PresentationPolicy.language(currentLanguage(context));if(language.isEmpty())return context
            val config=Configuration(context.resources.configuration);config.setLocales(LocaleList.forLanguageTags(language));return context.createConfigurationContext(config)
        }
    }
}
fun externalIntent(value:String):Intent {
    require(ExternalDestinations.valid(value))
    return Intent(if(value.startsWith("mailto:"))Intent.ACTION_SENDTO else Intent.ACTION_VIEW,android.net.Uri.parse(value))
}

fun openExternal(context:Context,value:String){
    try{context.startActivity(externalIntent(value))}catch(_:Exception){android.widget.Toast.makeText(context,context.getString(com.massivex.android.R.string.link_failed),android.widget.Toast.LENGTH_SHORT).show()}
}
