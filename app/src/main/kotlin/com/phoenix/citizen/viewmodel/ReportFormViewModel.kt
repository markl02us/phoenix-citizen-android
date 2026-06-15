package com.phoenix.citizen.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.citizen.data.model.LocationSource
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
    val error: String? = null,
    /** GPS accuracy radius (m) of the seeded fix; cleared once the user edits. */
    val accuracyM: Float? = null,
    /** True while lat/lon still hold the unedited GPS seed. A manual overtype
     *  (or a screen opened with caller-supplied initial coords) flips this false,
     *  which is exactly the gps_form → manual_edited provenance distinction. */
    val gpsSeeded: Boolean = false
)

class ReportFormViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ReportRepository(app)
    private val loc = LocationProvider(app)

    private val _state = MutableStateFlow(FormState())
    val state: StateFlow<FormState> = _state.asStateFlow()

    fun seed(lat: Double?, lon: Double?) {
        if (lat != null && lon != null) {
            // Caller-supplied coords (e.g. a map tap) are NOT a raw device GPS
            // fix — treat as manual provenance: gpsSeeded stays false.
            _state.update { it.copy(lat = lat, lon = lon, accuracyM = null, gpsSeeded = false) }
        } else {
            // Auto-acquire a real GPS fix; remember it is an unedited seed so a
            // straight submit is gps_form (precise) until the user overtypes.
            viewModelScope.launch {
                val fix = loc.currentFixOrNull()
                fix?.let { f ->
                    _state.update {
                        it.copy(lat = f.lat, lon = f.lon, accuracyM = f.accuracyM, gpsSeeded = true)
                    }
                }
            }
        }
    }

    // Any manual overtype invalidates the GPS seed: the value is now user-typed,
    // so accuracy is unknown and provenance becomes manual_edited at submit.
    fun setLat(v: String) {
        _state.update { it.copy(lat = v.toDoubleOrNull(), gpsSeeded = false, accuracyM = null) }
    }
    fun setLon(v: String) {
        _state.update { it.copy(lon = v.toDoubleOrNull(), gpsSeeded = false, accuracyM = null) }
    }
    fun setObservation(o: ObservationType) { _state.update { it.copy(observation = o) } }
    fun setWind(w: WindDirection) { _state.update { it.copy(wind = w) } }
    fun setNote(n: String) { _state.update { it.copy(note = n.take(500)) } }
    fun setPhoto(uri: Uri?) { _state.update { it.copy(photoUri = uri) } }

    fun submit() {
        val s = _state.value
        val lat = s.lat ?: return _state.update { it.copy(error = "missing_lat") }
        val lon = s.lon ?: return _state.update { it.copy(error = "missing_lon") }
        // gps_form when the seeded GPS fix was never edited; manual_edited once
        // the user overtyped (or opened the form with caller-supplied coords).
        val source = if (s.gpsSeeded) LocationSource.GPS_FORM else LocationSource.MANUAL_EDITED
        val accuracy = if (s.gpsSeeded) s.accuracyM else null
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            repo.submitOrQueue(
                lat = lat,
                lon = lon,
                tsUtc = TimeUtils.nowUtcIso(),
                observationType = s.observation.wire,
                windDirection = s.wind.wire.takeIf { it != WindDirection.UNKNOWN.wire },
                photoPath = s.photoUri?.toString(),
                note = s.note.ifBlank { null },
                accuracyM = accuracy,
                locationSource = source.wire
            )
            _state.update { it.copy(submitting = false, submitted = true) }
        }
    }
}
