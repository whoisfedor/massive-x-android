package com.massivex.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal fun shortPublic(value:String)=if(value.length>24)value.take(9)+"…"+value.takeLast(7)else value
@Composable internal fun PublicCopy(value:String,copy:(String)->Unit,compact:Boolean=false,modifier:Modifier=Modifier,balanced:Boolean=false){
    var copied by remember(value){mutableStateOf(false)}
    LaunchedEffect(copied){if(copied){delay(2000);copied=false}}
    Text(if(copied)stringResource(R.string.copied_public)else if(compact)shortPublic(value)else if(balanced)balancedPublic(value)else value,modifier.semantics{if(balanced&&!copied)contentDescription=value}.heightIn(min=48.dp).clickable{copy(value);copied=true}.padding(vertical=12.dp),fontSize=if(balanced)17.sp else 13.sp,textAlign=if(balanced)androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,color=MaterialTheme.colorScheme.primary)
}
@Composable internal fun DetailValue(label:String,value:String){
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){Text(label,Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp);Text(value,Modifier.weight(1.2f),fontSize=14.sp,textAlign=androidx.compose.ui.text.style.TextAlign.End)}
}

internal fun balancedPublic(value:String):String = value.chunked((value.length+1)/2).joinToString("\n")
