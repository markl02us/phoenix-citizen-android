package com.phoenix.citizen.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.citizen.data.model.SourceHealth
import com.phoenix.citizen.data.repository.DevicePrefs
import com.phoenix.citizen.data.repository.ReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = DevicePrefs(app)
    private val repo = ReportRepository(app)

    val language: StateFlow<String> = prefs.languageFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "system")
    val pushEnabled: StateFlow<Boolean> = prefs.pushEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val deviceHash: StateFlow<String> = prefs.deviceHashFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val reputationTier: StateFlow<String> = prefs.reputationTierFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "new")

    private val _health = MutableStateFlow<SourceHealth?>(null)
    val health: StateFlow<SourceHealth?> = _health.asStateFlow()

    init {
        viewModelScope.launch { prefs.getOrCreateDeviceHash() }
        pingHealth()
    }

    fun setLanguage(code: String) { viewModelScope.launch { prefs.setLanguage(code) } }
    fun setPushEnabled(enabled: Boolean) { viewModelScope.launch { prefs.setPushEnabled(enabled) } }
    fun pingHealth() {
        viewModelScope.launch { _health.value = repo.fetchSourceHealth() }
    }
}
