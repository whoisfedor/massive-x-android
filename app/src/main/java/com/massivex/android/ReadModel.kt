package com.massivex.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.massivex.android.network.*

class ReadModel(app: Application) : AndroidViewModel(app) {
    private val preferences = app.getSharedPreferences("public-display-v1", Context.MODE_PRIVATE)
    private val display = object : DisplayPreferences {
        override var currency: String
            get() = preferences.getString("currency", "USD")?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: "USD"
            set(value) { preferences.edit().putString("currency", value).apply() }
        override var fiat: Boolean
            get() = preferences.getBoolean("fiat", false)
            set(value) { preferences.edit().putBoolean("fiat", value).apply() }
        override var filter: ActivityFilter
            get() = runCatching { ActivityFilter.valueOf(preferences.getString("filter", "ALL")!!) }.getOrDefault(ActivityFilter.ALL)
            set(value) { preferences.edit().putString("filter", value.name).apply() }
    }
    private val transport = PlatformReadTransport()
    val reads = ReadCoordinator(viewModelScope, MainnetReads(transport), ExplorerReads(transport), PublicMarketReads(transport), display)
    override fun onCleared() { reads.stop() }
}
