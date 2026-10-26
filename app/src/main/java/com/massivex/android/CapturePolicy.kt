package com.massivex.android

import android.view.Window
import android.view.WindowManager

/** Window protection precedes secret rendering; release follows two completed frame boundaries. */
internal class CapturePolicy(private val window: Window) {
    private var generation = 0
    private var desired: Boolean? = null
    fun sensitive(value: Boolean) {
        if (desired == value) return
        desired = value
        val request = ++generation
        if (value) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.decorView.postOnAnimation {
            window.decorView.postOnAnimation {
                if (generation == request && desired == false) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}
