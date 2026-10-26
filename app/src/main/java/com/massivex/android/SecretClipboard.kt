package com.massivex.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import java.util.UUID

/** Holds only an ownership token, never a second copy of clipboard contents. Main thread only. */
internal class SecretClipboard(context: Context) {
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    fun copy(key: String) {
        val label = "MassiveX/backup/" + UUID.randomUUID()
        val clip = ClipData.newPlainText(label, key)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        clipboard.setPrimaryClip(clip)
        ownedLabel = label
    }
    fun ownsCurrent(): Boolean = ownedLabel != null && clipboard.primaryClipDescription?.label?.toString() == ownedLabel
    fun clear() {
        if (ownsCurrent()) {
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        ownedLabel = null
    }
    private companion object { var ownedLabel: String? = null }
}
