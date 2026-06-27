package com.phoenix.citizen.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.citizen.data.api.NetworkModule
import com.phoenix.citizen.data.model.CitizenConfirmRequest
import com.phoenix.citizen.data.repository.DevicePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

data class CitizenConfirmState(
    val inFlight: Boolean = false,
    val lastConfirmCount: Int? = null,
    val lastMessage: String? = null,
)

/**
 * "I see this fire too." Calls /api/citizen_confirm. Used from the citizen
 * marker bottom sheet on the map. No DGX involvement — backend writes
 * straight to D1, the confirm count on the map increments within seconds.
 */
class CitizenConfirmViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(CitizenConfirmState())
    val state: StateFlow<CitizenConfirmState> = _state.asStateFlow()

    fun confirm(reportId: Long) {
        if (_state.value.inFlight) return
        _state.value = _state.value.copy(inFlight = true, lastMessage = null)
        viewModelScope.launch {
            val deviceId = DevicePrefs(app).getOrCreateDeviceHash()
            val token = UUID.randomUUID().toString()
            val isIt = Locale.getDefault().language == "it"
            try {
                val resp = NetworkModule.apiV2.citizenConfirm(
                    CitizenConfirmRequest(
                        clientToken = token,
                        reportId = reportId,
                        deviceId = deviceId,
                    )
                )
                val msg = when {
                    resp.isSuccessful -> {
                        val body = resp.body()
                        val human = body?.userMessage?.let { if (isIt) it.it else it.en }
                        human ?: (if (isIt) "Conferma registrata." else "Confirmation recorded.")
                    }
                    resp.code() == 404 -> if (isIt) "La conferma non è disponibile su questo backend." else "Confirm not available on this backend."
                    else -> if (isIt) "Errore: ${resp.code()}" else "Error: ${resp.code()}"
                }
                _state.value = CitizenConfirmState(
                    inFlight = false,
                    lastConfirmCount = resp.body()?.confirmCount,
                    lastMessage = msg,
                )
            } catch (e: Throwable) {
                _state.value = CitizenConfirmState(
                    inFlight = false,
                    lastMessage = if (isIt) "Connessione non riuscita. Riprova." else "Network failed. Try again.",
                )
            }
        }
    }
}
