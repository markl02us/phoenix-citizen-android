package com.phoenix.citizen.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.citizen.data.model.ObservationType
import com.phoenix.citizen.data.model.WindDirection
import com.phoenix.citizen.data.repository.ReportRepository
import com.phoenix.citizen.util.LocationProvider
import com.phoenix.citizen.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FormState(
    val lat: Double? = null,
    val lon: Double? = null,
    val observation: ObservationType = ObservationType.FLAME,
    val wind: WindDirection = WindDirection.UNKNOWN,
    val photoUri: Uri? = null,
    val note: String = "",
    val submitting: Boolean = false,
    val submitted: Boolean = false,
    val error: String? = null
)

class ReportFormViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ReportRepository(app)
    private val loc = LocationProvider(app)

    private val _state = MutableStateFlow(FormState())
    val state: StateFlow<FormState> = _state.asStateFlow()

    fun seed(lat: Double?, lon: Double?) {
        if (lat != null && lon != null) {
            _state.update { it.copy(lat = lat, lon = lon) }
        } else {
            // Try to auto-acquire GPS
            viewModelScope.launch {
                val pt = loc.currentOrNull()
                pt?.let { (la, lo) -> _state.update { it.copy(lat = la, lon = lo) } }
            }
        }
    }

    fun setLat(v: String) { _state.update { it.copy(lat = v.toDoubleOrNull()) } }
    fun setLon(v: String) { _state.update { it.copy(lon = v.toDoubleOrNull()) } }
    fun setObservation(o: ObservationType) { _state.update { it.copy(observation = o) } }
    fun setWind(w: WindDirection) { _state.update { it.copy(wind = w) } }
    fun setNote(n: String) { _state.update { it.copy(note = n.take(500)) } }
    fun setPhoto(uri: Uri?) { _state.update { it.copy(photoUri = uri) } }

    fun submit() {
        val s = _state.value
        val lat = s.lat ?: return _state.update { it.copy(error = "missing_lat") }
        val lon = s.lon ?: return _state.update { it.copy(error = "missing_lon") }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            repo.submitOrQueue(
                lat = lat,
                lon = lon,
                tsUtc = TimeUtils.nowUtcIso(),
                observationType = s.observation.wire,
                windDirection = s.wind.wire.takeIf { it != WindDirection.UNKNOWN.wire },
                photoPath = s.photoUri?.toString(),
                note = s.note.ifBlank { null }
            )
            _state.update { it.copy(submitting = false, submitted = true) }
        }
    }
}
