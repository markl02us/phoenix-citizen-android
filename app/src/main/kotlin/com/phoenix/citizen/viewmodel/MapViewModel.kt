package com.phoenix.citizen.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.citizen.data.model.Detection
import com.phoenix.citizen.data.repository.ReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MapState(
    val detections: List<Detection> = emptyList(),
    val loading: Boolean = false
)

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ReportRepository(app)
    private val _state = MutableStateFlow(MapState())
    val state: StateFlow<MapState> = _state.asStateFlow()

    fun loadForViewport(south: Double, west: Double, north: Double, east: Double) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val list = repo.fetchDetections("24h", south, west, north, east)
            _state.value = MapState(detections = list, loading = false)
        }
    }
}
