package com.phoenix.citizen.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoenix.citizen.R
import com.phoenix.citizen.data.db.ReportEntity
import com.phoenix.citizen.util.TimeUtils
import com.phoenix.citizen.viewmodel.HistoryViewModel

@Composable
fun HistoryScreen(vm: HistoryViewModel = viewModel()) {
    val reports by vm.reports.collectAsState()
    val refreshing by vm.refreshing.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = vm::refresh,
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.pull_to_refresh))
        }
        Spacer(Modifier.height(8.dp))
        if (reports.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(reports, key = { it.id }) { row ->
                    ReportCard(row, onRetry = { vm.retry(row.id) })
                }
            }
        }
    }
}

@Composable
private fun ReportCard(row: ReportEntity, onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${row.observationType.uppercase()} · ${TimeUtils.formatLocal(row.tsUtc)}",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text("lat=${"%.5f".format(row.lat)}, lon=${"%.5f".format(row.lon)}")
            row.note?.let { Text("note: $it") }
            row.windDirection?.let { Text("wind from: $it") }
            Spacer(Modifier.height(8.dp))
            Text(
                when (row.status) {
                    ReportEntity.STATUS_SYNCED -> stringResource(R.string.history_synced)
                    ReportEntity.STATUS_QUEUED -> stringResource(R.string.history_queued)
                    ReportEntity.STATUS_FAILED -> stringResource(R.string.history_failed)
                    else -> row.status
                }
            )
            if (row.status == ReportEntity.STATUS_SYNCED) {
                if (row.corroboratedAt != null && !row.corroboratingSources.isNullOrBlank()) {
                    Text(
                        stringResource(
                            R.string.history_confirmed_by,
                            row.corroboratingSources
                        )
                    )
                } else {
                    Text(stringResource(R.string.history_awaiting))
                }
            }
            if (row.status == ReportEntity.STATUS_FAILED) {
                row.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Button(onClick = onRetry) { Text(stringResource(R.string.history_retry)) }
            }
        }
    }
}
