package com.phoenix.citizen.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.phoenix.citizen.R
import com.phoenix.citizen.data.model.ObservationType
import com.phoenix.citizen.data.model.WindDirection
import com.phoenix.citizen.viewmodel.ReportFormViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ReportFormScreen(
    initialLat: Double?,
    initialLon: Double?,
    onSubmitted: () -> Unit,
    vm: ReportFormViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsState()
    val camPerm = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(initialLat, initialLon) { vm.seed(initialLat, initialLon) }
    LaunchedEffect(state.submitted) { if (state.submitted) onSubmitted() }

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok && pendingUri != null) vm.setPhoto(pendingUri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                stringResource(R.string.report_form_title),
                style = MaterialTheme.typography.headlineSmall
            )
        }

        // Photo section
        item {
            Text(stringResource(R.string.report_form_photo), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    if (!camPerm.status.isGranted()) {
                        camPerm.launchPermissionRequest()
                        return@Button
                    }
                    val uri = createCaptureUri(ctx)
                    pendingUri = uri
                    cameraLauncher.launch(uri)
                }) {
                    Text(
                        if (state.photoUri == null) stringResource(R.string.report_form_capture)
                        else stringResource(R.string.report_form_retake)
                    )
                }
                if (state.photoUri != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.setPhoto(null) }) {
                        Text(stringResource(R.string.report_form_remove))
                    }
                }
            }
            state.photoUri?.let {
                Card {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                }
            }
        }

        // Observation type
        item {
            Text(stringResource(R.string.report_form_observation), style = MaterialTheme.typography.titleMedium)
            ObservationType.entries.forEach { opt ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.observation == opt,
                        onClick = { vm.setObservation(opt) }
                    )
                    Text(
                        when (opt) {
                            ObservationType.FLAME -> stringResource(R.string.obs_flame)
                            ObservationType.SMOKE -> stringResource(R.string.obs_smoke)
                            ObservationType.UNSURE -> stringResource(R.string.obs_unsure)
                        }
                    )
                }
            }
        }

        // Wind direction
        item {
            Text(stringResource(R.string.report_form_wind), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        }
        items(WindDirection.entries.chunked(3)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { dir ->
                    FilterChip(
                        selected = state.wind == dir,
                        onClick = { vm.setWind(dir) },
                        label = { Text(dir.label()) }
                    )
                }
            }
        }

        // Note
        item {
            OutlinedTextField(
                value = state.note,
                onValueChange = vm::setNote,
                label = { Text(stringResource(R.string.report_form_note)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )
        }

        // Lat / Lon
        item {
            OutlinedTextField(
                value = state.lat?.toString().orEmpty(),
                onValueChange = vm::setLat,
                label = { Text(stringResource(R.string.report_form_lat)) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.lon?.toString().orEmpty(),
                onValueChange = vm::setLon,
                label = { Text(stringResource(R.string.report_form_lon)) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Submit
        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.submit() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting && state.lat != null && state.lon != null
            ) {
                Text(stringResource(R.string.report_form_submit))
            }
        }
    }
}

@Composable
private fun WindDirection.label(): String = when (this) {
    WindDirection.UNKNOWN -> stringResource(R.string.wind_unknown)
    WindDirection.N -> stringResource(R.string.wind_n)
    WindDirection.NE -> stringResource(R.string.wind_ne)
    WindDirection.E -> stringResource(R.string.wind_e)
    WindDirection.SE -> stringResource(R.string.wind_se)
    WindDirection.S -> stringResource(R.string.wind_s)
    WindDirection.SW -> stringResource(R.string.wind_sw)
    WindDirection.W -> stringResource(R.string.wind_w)
    WindDirection.NW -> stringResource(R.string.wind_nw)
}

private fun createCaptureUri(ctx: Context): Uri {
    val cacheDir = File(ctx.cacheDir, "captures").apply { mkdirs() }
    val name = "fire_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    val file = File(cacheDir, name)
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}

@OptIn(ExperimentalPermissionsApi::class)
private fun com.google.accompanist.permissions.PermissionStatus.isGranted(): Boolean =
    this is com.google.accompanist.permissions.PermissionStatus.Granted
