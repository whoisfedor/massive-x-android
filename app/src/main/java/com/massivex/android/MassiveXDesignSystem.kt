package com.massivex.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.res.stringResource

internal object MX { val sectionGap=20.dp; val gutter=20.dp; val radius=20.dp; val touch=48.dp }
internal enum class MXGlyph { BACK, WALLET, ACTIVITY, SETTINGS, OUT, IN, EYE, INFO, REFRESH, CLOSE, CHEVRON, PLUS, CHECK, GLOBE, CURRENCY, PALETTE, LANGUAGE, BELL, APPEARANCE, GRID, COPY, SHARE, TRASH }
/** Original native stroke icons; no screenshot crops, remote fonts or icon dependency. */
@Composable internal fun MXIcon(glyph:MXGlyph,modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.primary){
    Canvas(modifier.size(24.dp).clearAndSetSemantics{}){
        scale(size.width/24f,size.height/24f,pivot=Offset.Zero){
            val stroke=Stroke(1.65f,cap=StrokeCap.Round,join=StrokeJoin.Round)
            fun line(a:Float,b:Float,c:Float,d:Float)=drawLine(color,Offset(a,b),Offset(c,d),1.65f,StrokeCap.Round)
            fun rect(x:Float,y:Float,w:Float,h:Float,r:Float=2f)=drawRoundRect(color,Offset(x,y),Size(w,h),CornerRadius(r),style=stroke)
            when(glyph){
                MXGlyph.WALLET->{rect(3f,6f,18f,14f);line(4f,6f,17f,2.5f);line(17f,2.5f,18f,6f);rect(14f,10f,8f,6f);drawCircle(color,1f,Offset(17f,13f))}
                MXGlyph.OUT->{line(5f,19f,19f,5f);line(10f,5f,19f,5f);line(19f,5f,19f,14f)}
                MXGlyph.IN->{line(19f,5f,5f,19f);line(5f,10f,5f,19f);line(5f,19f,14f,19f)}
                MXGlyph.BACK->{line(15f,5f,8f,12f);line(8f,12f,15f,19f)}
                MXGlyph.CHEVRON->{line(9f,5f,16f,12f);line(16f,12f,9f,19f)}
                MXGlyph.CLOSE->{line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
                MXGlyph.PLUS->{line(12f,4f,12f,20f);line(4f,12f,20f,12f)}
                MXGlyph.CHECK->{line(5f,12f,10f,17f);line(10f,17f,20f,6f)}
                MXGlyph.EYE->{val p=Path().apply{moveTo(2f,12f);cubicTo(7f,3f,17f,3f,22f,12f);cubicTo(17f,21f,7f,21f,2f,12f)};drawPath(p,color,style=stroke);drawCircle(color,3f,Offset(12f,12f),style=stroke)}
                MXGlyph.INFO->{drawCircle(color,9f,Offset(12f,12f),style=stroke);line(12f,11f,12f,17f);drawCircle(color,1f,Offset(12f,7f))}
                MXGlyph.GLOBE,MXGlyph.LANGUAGE->{drawCircle(color,9f,Offset(12f,12f),style=stroke);drawOval(color,Offset(8f,3f),Size(8f,18f),style=stroke);line(3f,12f,21f,12f);line(5f,7f,19f,7f);line(5f,17f,19f,17f)}
                MXGlyph.CURRENCY->{drawCircle(color,9f,Offset(12f,12f),style=stroke);line(12f,5f,12f,19f);val p=Path().apply{moveTo(16f,8f);cubicTo(6f,3f,6f,12f,12f,12f);cubicTo(19f,12f,17f,21f,7f,16f)};drawPath(p,color,style=stroke)}
                MXGlyph.COPY->{rect(8f,7f,12f,14f);rect(4f,3f,12f,14f)}
                MXGlyph.SHARE->{rect(4f,10f,16f,11f);line(12f,15f,12f,2f);line(7f,7f,12f,2f);line(12f,2f,17f,7f)}
                MXGlyph.GRID->{for(x in listOf(3f,14f))for(y in listOf(3f,14f))rect(x,y,7f,7f,1f)}
                MXGlyph.APPEARANCE->{drawCircle(color,9f,Offset(12f,12f),style=stroke);drawArc(color,-90f,180f,true,Offset(3f,3f),Size(18f,18f))}
                MXGlyph.ACTIVITY,MXGlyph.REFRESH->{drawArc(color,45f,285f,false,Offset(4f,4f),Size(16f,16f),style=stroke);if(glyph==MXGlyph.ACTIVITY){line(12f,7f,12f,12f);line(12f,12f,16f,14f)}else{line(15f,1f,19f,5f);line(19f,5f,15f,9f)}}
                MXGlyph.TRASH->{rect(6f,7f,12f,14f);line(3f,6f,21f,6f);line(9f,3f,15f,3f);line(10f,10f,10f,18f);line(14f,10f,14f,18f)}
                MXGlyph.BELL->{val p=Path().apply{moveTo(4f,17f);lineTo(7f,13f);lineTo(7f,8f);cubicTo(7f,1f,17f,1f,17f,8f);lineTo(17f,13f);lineTo(20f,17f);close()};drawPath(p,color,style=stroke);drawArc(color,0f,180f,false,Offset(10f,18f),Size(4f,4f),style=stroke)}
                MXGlyph.PALETTE->{drawCircle(color,9f,Offset(12f,12f),style=stroke);for(p in listOf(Offset(8f,8f),Offset(14f,6f),Offset(18f,11f),Offset(7f,14f)))drawCircle(color,1.5f,p)}
                MXGlyph.SETTINGS->{for(i in 0..7){rotate(i*45f,pivot=Offset(12f,12f)){rect(10f,1f,4f,6f,1f)}};drawCircle(color,7f,Offset(12f,12f),style=stroke);drawCircle(color,2.5f,Offset(12f,12f),style=stroke)}
            }
        }
    }
}
@Composable internal fun MXPanel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(MX.radius),color=MaterialTheme.colorScheme.surface){Column(content=content)}
}
@Composable internal fun MXIconAction(glyph:MXGlyph,label:String,enabled:Boolean=true,action:()->Unit){
    Box(Modifier.size(MX.touch).clickable(enabled=enabled,role=Role.Button,onClick=action).semantics{contentDescription=label},contentAlignment=Alignment.Center){MXIcon(glyph,color=MaterialTheme.colorScheme.primary.copy(alpha=if(enabled)1f else .35f))}
}
@Composable internal fun MXButton(label:String,enabled:Boolean=true,modifier:Modifier=Modifier,primary:Boolean=true,icon:MXGlyph?=null,action:()->Unit){
    val colors=MaterialTheme.colorScheme
    Surface(onClick=action,enabled=enabled,modifier=modifier.fillMaxWidth().heightIn(min=54.dp),shape=RoundedCornerShape(18.dp),color=if(primary)colors.primary.copy(alpha=if(enabled)1f else .35f)else colors.primaryContainer){
        Row(Modifier.padding(horizontal=16.dp,vertical=14.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
            icon?.let{MXIcon(it,color=if(primary)colors.onPrimary else colors.primary);Spacer(Modifier.width(8.dp))}
            Text(label,color=if(primary)colors.onPrimary else colors.primary,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
        }
    }
}
@Composable internal fun MXRow(label:String,glyph:MXGlyph?,value:String?=null,enabled:Boolean=true,accent:Boolean=false,disclosure:Boolean=true,onClick:()->Unit){
    Surface(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().padding(bottom=3.dp),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surface){
        Row(Modifier.padding(horizontal=16.dp,vertical=13.dp).heightIn(min=22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
            glyph?.let{MXIcon(it)};Text(label,Modifier.weight(1f),fontSize=17.sp,color=if(accent)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            value?.takeIf{it.isNotEmpty()}?.let{Text(it,Modifier.widthIn(max=128.dp),fontSize=15.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(disclosure)MXIcon(MXGlyph.CHEVRON,Modifier.size(14.dp),MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable internal fun MXHeading(text:String,actions:@Composable RowScope.()->Unit={}){
    Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=18.dp),verticalAlignment=Alignment.CenterVertically){Text(text,Modifier.weight(1f),fontSize=32.sp,fontWeight=FontWeight.Bold);actions()}
}
@Composable internal fun MXTabs(screen:Screen,hasWallet:Boolean,navigate:(Screen)->Unit){
    Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.97f)){
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top=8.dp,bottom=6.dp)){
            listOf(Triple(Screen.HOME,R.string.ui_wallet,MXGlyph.WALLET),Triple(Screen.ACTIVITY,R.string.activity,MXGlyph.ACTIVITY),Triple(Screen.SETTINGS,R.string.settings,MXGlyph.SETTINGS)).forEach{(destination,label,glyph)->
                val selected=screen==destination;val enabled=destination!=Screen.ACTIVITY||hasWallet
                val color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Column(Modifier.weight(1f).heightIn(min=50.dp).clickable(enabled=enabled,role=Role.Tab){navigate(destination)}.semantics{this.selected=selected},horizontalAlignment=Alignment.CenterHorizontally){MXIcon(glyph,Modifier.size(27.dp),color);Text(stringResource(label),fontSize=10.sp,color=color)}
            }
        }
    }
}
