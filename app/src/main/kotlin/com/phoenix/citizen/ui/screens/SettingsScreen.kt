package com.phoenix.citizen.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.phoenix.citizen.BuildConfig
import com.phoenix.citizen.R
import com.phoenix.citizen.viewmodel.SettingsViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val ctx = LocalContext.current
    val language by vm.language.collectAsState()
    val pushEnabled by vm.pushEnabled.collectAsState()
    val deviceHash by vm.deviceHash.collectAsState()
    val tier by vm.reputationTier.collectAsState()
    val health by vm.health.collectAsState()

    val locPerm = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val camPerm = rememberPermissionState(Manifest.permission.CAMERA)
    val notPerm = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        // Language
        SettingsSection(stringResource(R.string.settings_language)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to R.string.settings_lang_system,
                    "en" to R.string.settings_lang_en,
                    "it" to R.string.settings_lang_it
                ).forEach { (code, label) ->
                    FilterChip(
                        selected = language == code,
                        onClick = { vm.setLanguage(code) },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
        }

        // Device
        SettingsSection(stringResource(R.string.settings_device)) {
            Text("${stringResource(R.string.settings_device_hash)}: ${deviceHash.take(8)}…${deviceHash.takeLast(4)}")
            Text("${stringResource(R.string.settings_reputation)}: ${tier.uppercase()}")
        }

        // Notifications
        SettingsSection(stringResource(R.string.settings_notifications)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_push_toggle), modifier = Modifier.weight(1f))
                Switch(checked = pushEnabled, onCheckedChange = vm::setPushEnabled)
            }
        }

        // Permissions
        SettingsSection(stringResource(R.string.settings_permissions)) {
            PermissionRow(R.string.settings_perm_location, locPerm.status.isGranted) {
                locPerm.launchPermissionRequest()
            }
            PermissionRow(R.string.settings_perm_camera, camPerm.status.isGranted) {
                camPerm.launchPermissionRequest()
            }
            PermissionRow(R.string.settings_perm_notifications, notPerm.status.isGranted) {
                notPerm.launchPermissionRequest()
            }
        }

        // Diagnostic
        SettingsSection(stringResource(R.string.settings_diagnostic)) {
            val h = health
            Text(
                when {
                    h == null -> stringResource(R.string.loading)
                    h.warnings.isEmpty() -> stringResource(R.string.settings_diag_ok)
                    else -> stringResource(R.string.settings_diag_degraded)
                }
            )
            h?.let {
                Text(
                    "sources: ${it.count} · warnings: ${it.warnings.size}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (it.warnings.isNotEmpty()) {
                    val preview = it.warnings.take(5).joinToString(", ") { w ->
                        val sev = w.severity?.let { s -> " ($s)" } ?: ""
                        "${w.source}$sev"
                    }
                    Text(preview, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = vm::pingHealth) { Text(stringResource(R.string.settings_diag_check)) }
        }

        // Privacy + About
        SettingsSection(stringResource(R.string.settings_privacy)) {
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://adr-wildfire.com/privacy")))
            }) { Text("https://adr-wildfire.com/privacy") }
        }

        SettingsSection(stringResource(R.string.settings_about)) {
            Text("${stringResource(R.string.settings_version)}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            TextButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://adr-wildfire.com/come-funziona")))
            }) { Text(stringResource(R.string.settings_how_it_works)) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.about_credits), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Divider(Modifier.padding(vertical = 6.dp))
            content()
        }
    }
}

@Composable
private fun PermissionRow(labelRes: Int, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(labelRes), modifier = Modifier.weight(1f))
        if (granted) Text("✅") else TextButton(onClick = onGrant) {
            Text(stringResource(R.string.perm_grant))
        }
    }
}
