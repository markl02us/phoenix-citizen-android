package com.phoenix.citizen.ui.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.phoenix.citizen.R
import com.phoenix.citizen.viewmodel.QuickReportViewModel
import com.phoenix.citizen.viewmodel.QuickState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QuickReportScreen(
    onMoreDetails: () -> Unit,
    vm: QuickReportViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val locPerm = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status / submitted card area
        when (val s = state) {
            is QuickState.Submitted -> {
                SubmittedCard(lat = s.lat, lon = s.lon, online = s.online, onDismiss = vm::reset)
            }
            is QuickState.LocationFailed -> {
                Text(
                    text = stringResource(R.string.location_failed),
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(16.dp))
            }
            else -> Unit
        }

        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (locPerm.status.isGranted) {
                        vm.submitFlameAtCurrentLocation()
                    } else {
                        locPerm.launchPermissionRequest()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = CircleShape,
                modifier = Modifier.size(240.dp)
            ) {
                when (state) {
                    is QuickState.Acquiring, is QuickState.Submitting -> {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.quick_report_button),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.quick_report_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        TextButton(onClick = onMoreDetails) {
            Text(stringResource(R.string.quick_report_more))
        }
    }
}

@Composable
private fun SubmittedCard(lat: Double, lon: Double, online: Boolean, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(if (online) R.string.quick_report_success else R.string.quick_report_queued),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text("lat=${"%.5f".format(lat)}, lon=${"%.5f".format(lon)}")
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    }
}

