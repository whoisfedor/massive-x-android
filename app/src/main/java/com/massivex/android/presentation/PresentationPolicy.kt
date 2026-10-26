package com.massivex.android.presentation

import com.massivex.massa.MassaCodec
import java.net.URI

enum class Appearance { DARK, LIGHT, SYSTEM }
enum class Accent { ORANGE, MIDNIGHT, CRIMSON }
enum class LauncherIcon(val alias:String,val code:String) { CLASSIC("Classic","001"), MIDNIGHT("Midnight","002"), AUREUM("Aureum","003"), CRIMSON("Crimson","004") }
data class Presentation(val appearance:Appearance=Appearance.DARK,val accent:Accent=Accent.CRIMSON,val icon:LauncherIcon=LauncherIcon.CLASSIC,val language:String="",val balanceAlerts:Boolean=false,val balanceHidden:Boolean=false)
object PresentationPolicy {
    val languages=listOf("en","fr","ru","es","zh-Hans","de")
    fun language(value:String)=value.takeIf{it in languages}.orEmpty()
    fun dark(appearance:Appearance,systemDark:Boolean)=when(appearance){Appearance.DARK->true;Appearance.LIGHT->false;Appearance.SYSTEM->systemDark}
}
/** Public fixed destinations, never wallet data, queries, affiliate parameters or arbitrary browser URLs. */
object ExternalDestinations {
    val fixed=linkedMapOf("website" to "https://massive-x.com/","privacy" to "https://github.com/whoisfedor/massive-x-android/blob/main/PRIVACY.md","support" to "mailto:info@massive-x.com",
        "explorer" to "https://explorer.massa.net/","dusa" to "https://app.dusa.io/","bridge" to "https://bridge.massa.net/index",
        "eaglefi" to "https://eaglefi-dex.deweb.node-master.fr/","massa" to "https://massa.network/",
        "bitget" to "https://www.bitget.com/spot/MASUSDT","mexc" to "https://www.mexc.com/exchange/MAS_USDT")
    fun valid(value:String):Boolean {
        if(value in fixed.values)return true
        return runCatching {
            val u=URI(value)
            require(u.scheme=="https"&&u.host=="explorer.massa.net"&&u.port==-1&&u.userInfo==null&&u.fragment==null&&u.query==null)
            val id=u.rawPath.removePrefix("/mainnet/operation/");require(u.rawPath=="/mainnet/operation/$id")
            MassaCodec.decode(id,"O");true
        }.getOrDefault(false)
    }
}
