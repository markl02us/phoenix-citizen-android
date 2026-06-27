package com.phoenix.citizen.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.citizen.alerts.AreaAlertChecker
import com.phoenix.citizen.data.model.WatchArea
import com.phoenix.citizen.data.repository.AreaAlertStore
import com.phoenix.citizen.data.repository.DevicePrefs
import com.phoenix.citizen.util.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class AreaAlertsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AreaAlertStore(app)
    private val checker = AreaAlertChecker(app)
    private val location = LocationProvider(app)
    private val prefs = DevicePrefs(app)

    val areas: StateFlow<List<WatchArea>> = store.areasFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Master notifications switch (shared with Settings). Surfaced so the Alerts
     *  screen can tell the user when alerts are paused and offer to resume. */
    val pushEnabled: StateFlow<Boolean> = prefs.pushEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setPushEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setPushEnabled(enabled) }
    }

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _lastCheck = MutableStateFlow<AreaAlertChecker.CheckSummary?>(null)
    val lastCheck: StateFlow<AreaAlertChecker.CheckSummary?> = _lastCheck.asStateFlow()

    fun saveArea(
        existingId: String?,
        existingCreatedUtc: Long?,
        label: String,
        lat: Double,
        lon: Double,
        radiusKm: Double,
        approachKm: Double,
    ) {
        viewModelScope.launch {
            val name = label.ifBlank { "Area ${store.getAreas().size + 1}" }
            store.upsert(
                WatchArea(
                    id = existingId ?: UUID.randomUUID().toString(),
                    label = name,
                    lat = lat,
                    lon = lon,
                    radiusKm = radiusKm,
                    approachKm = approachKm,
                    enabled = true,
                    createdUtc = existingCreatedUtc ?: System.currentTimeMillis(),
                ),
            )
            // Start watching immediately — don't make the user wait for the 15-min
            // worker. Surfaces a notification right away if a fire is already there.
            runCatching { checker.runCheck() }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    /** Best-effort current GPS fix; null if no permission / no fix. */
    suspend fun currentLocation(): Pair<Double, Double>? = location.currentOrNull()

    fun checkNow() {
        viewModelScope.launch {
            _checking.value = true
            try {
                _lastCheck.value = checker.runCheck(force = true)
            } finally {
                _checking.value = false
            }
        }
    }
}
