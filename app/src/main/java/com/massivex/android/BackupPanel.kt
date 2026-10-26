package com.massivex.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Presentation only. Callers authenticate each reveal/copy; acknowledgment is a user statement. */
@Composable internal fun BackupPanel(saved:Boolean,acknowledge:()->Unit,revealed:String?,ownedCopy:Boolean,busy:Boolean,reveal:()->Unit,copy:()->Unit,clear:()->Unit){
    MXPanel {
        MXRow(stringResource(R.string.ui_saved_backup),if(saved)MXGlyph.CHECK else MXGlyph.WALLET,disclosure=false,onClick=acknowledge)
        HorizontalDivider(Modifier.padding(start=16.dp))
        Text(stringResource(R.string.backup_warning),Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)
    }
    MXPanel {
        MXRow(stringResource(if(revealed==null)R.string.reveal else R.string.ui03_hide_key),null,enabled=!busy,accent=true,disclosure=false,onClick=reveal)
        HorizontalDivider(Modifier.padding(start=16.dp))
        MXRow(stringResource(R.string.copy_private),null,enabled=!busy,accent=true,disclosure=false,onClick=copy)
        if(ownedCopy){HorizontalDivider(Modifier.padding(start=16.dp));MXRow(stringResource(R.string.ui03_clear_key),null,accent=true,disclosure=false,onClick=clear)}
    }
    revealed?.let { Text(it,Modifier.clearAndSetSemantics {},style=MaterialTheme.typography.bodyLarge) }
    MXPanel{Text(stringResource(R.string.ui03_backup_privacy),Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
}
