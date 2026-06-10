package com.phoenix.citizen.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoenix.citizen.data.db.ReportEntity
import com.phoenix.citizen.data.repository.ReportRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ReportRepository(app)

    val reports: StateFlow<List<ReportEntity>> =
        repo.observeReports().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                repo.drainQueue()
                repo.refreshCorroboration()
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun retry(rowId: Long) {
        viewModelScope.launch { repo.attemptSend(rowId) }
    }
}
