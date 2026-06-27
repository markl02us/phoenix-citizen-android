package com.phoenix.citizen.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.citizen.data.api.NetworkModule
import com.phoenix.citizen.data.model.SystemStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Polls /api/system_status every 60 s while the app is in the foreground.
 * Surfaces the 🟢🟡🔴 banner state at the top of the map.
 *
 * Graceful degrade: if the endpoint 404s (v1 backend during partial
 * rollback), assume green so the v1 backend doesn't look "broken" in the v2
 * app.
 */
class SystemStatusViewModel(app: Application) : AndroidViewModel(app) {

    private val _status = MutableStateFlow<SystemStatus?>(null)
    val status: StateFlow<SystemStatus?> = _status.asStateFlow()

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                try {
                    val resp = NetworkModule.apiV2.systemStatus()
                    when {
                        resp.isSuccessful -> _status.value = resp.body()
                        resp.code() == 404 -> _status.value = SystemStatus.unknown_green
                        else -> _status.value = SystemStatus.unknown_green
                    }
                } catch (e: Throwable) {
                    // Network error — keep the previous state so the banner
                    // doesn't flicker on a single failed poll.
                    if (_status.value == null) _status.value = SystemStatus.unknown_green
                }
                delay(60_000)
            }
        }
    }

    /** Force a refresh from outside (e.g. after submitting a report). */
    fun refresh() {
        viewModelScope.launch {
            try {
                val resp = NetworkModule.apiV2.systemStatus()
                if (resp.isSuccessful) _status.value = resp.body()
            } catch (_: Throwable) { /* keep current state */ }
        }
    }
}
