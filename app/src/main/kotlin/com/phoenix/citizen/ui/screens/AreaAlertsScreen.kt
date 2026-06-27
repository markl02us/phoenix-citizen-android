package com.phoenix.citizen.ui.screens

import android.Manifest
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.phoenix.citizen.R
import com.phoenix.citizen.data.model.WatchArea
import com.phoenix.citizen.viewmodel.AreaAlertsViewModel
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos
import kotlin.math.pow

private const val SICILY_LAT = 37.5
private const val SICILY_LON = 14.0

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun AreaAlertsScreen(vm: AreaAlertsViewModel = viewModel()) {
    val areas by vm.areas.collectAsState()
    val checking by vm.checking.collectAsState()
    val lastCheck by vm.lastCheck.collectAsState()
    val pushEnabled by vm.pushEnabled.collectAsState()
    val scope = rememberCoroutineScope()

    val notPerm = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    val locPerm = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingCreated by remember { mutableStateOf<Long?>(null) }
    var label by remember { mutableStateOf("") }
    var radiusKm by remember { mutableStateOf(10f) }
    var approachKm by remember { mutableStateOf(10f) }
    var pendingCenter by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var deleteTarget by remember { mutableStateOf<WatchArea?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.alerts_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.alerts_intro), style = MaterialTheme.typography.bodyMedium)

        // One clear readiness banner: either grant notifications, or resume if paused.
        when {
            !notPerm.status.isGranted -> NoticeCard(
                message = stringResource(R.string.alerts_need_notif),
                action = stringResource(R.string.settings_perm_notifications),
                onAction = { notPerm.launchPermissionRequest() },
            )
            !pushEnabled -> NoticeCard(
                message = stringResource(R.string.alerts_paused),
                action = stringResource(R.string.alerts_resume),
                onAction = { vm.setPushEnabled(true) },
            )
        }

        if (areas.isEmpty() && !editing) {
            Text(stringResource(R.string.alerts_empty), style = MaterialTheme.typography.bodyMedium)
        }

        areas.forEach { area ->
            AreaCard(
                area = area,
                onToggle = { vm.setEnabled(area.id, it) },
                onEdit = {
                    editingId = area.id
                    editingCreated = area.createdUtc
                    label = area.label
                    radiusKm = area.radiusKm.toFloat().coerceIn(1f, 50f)
                    approachKm = area.approachKm.toFloat().coerceIn(0f, 25f)
                    pendingCenter = null
                    editing = true
                },
                onDelete = { deleteTarget = area },
            )
        }

        if (!editing) {
            Button(
                onClick = {
                    editingId = null
                    editingCreated = null
                    label = ""
                    radiusKm = 10f
                    approachKm = 10f
                    pendingCenter = null
                    // Open immediately so the tap always responds; then fetch the
                    // user's location in the background and recentre the map onto it.
                    editing = true
                    if (locPerm.status.isGranted) {
                        scope.launch { pendingCenter = vm.currentLocation() }
                    } else {
                        locPerm.launchPermissionRequest()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.alerts_add))
            }

            OutlinedButton(
                onClick = { vm.checkNow() },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (checking) stringResource(R.string.alerts_checking)
                    else stringResource(R.string.alerts_check_now),
                )
            }

            lastCheck?.let { s ->
                val noneLbl = stringResource(R.string.alerts_check_none)
                val insideLbl = stringResource(R.string.alerts_inside)
                val approachingLbl = stringResource(R.string.alerts_approaching)
                val clearLbl = stringResource(R.string.alerts_check_clear)
                val summary = if (s.ranAreas == 0) {
                    noneLbl
                } else {
                    s.results.joinToString("\n") { r ->
                        if (r.inside == 0 && r.approaching == 0) {
                            "• ${r.label}: $clearLbl"
                        } else {
                            "• ${r.label}: ${r.inside} $insideLbl, ${r.approaching} $approachingLbl"
                        }
                    }
                }
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (editing) {
            val initLat = when {
                editingId != null -> areas.firstOrNull { it.id == editingId }?.lat ?: SICILY_LAT
                else -> pendingCenter?.first ?: SICILY_LAT
            }
            val initLon = when {
                editingId != null -> areas.firstOrNull { it.id == editingId }?.lon ?: SICILY_LON
                else -> pendingCenter?.second ?: SICILY_LON
            }
            AreaEditor(
                label = label,
                onLabel = { label = it },
                radiusKm = radiusKm,
                onRadius = { radiusKm = it },
                approachKm = approachKm,
                onApproach = { approachKm = it },
                initialLat = initLat,
                initialLon = initLon,
                recenterTo = if (editingId == null) pendingCenter else null,
                onUseMyLocation = { recenter ->
                    if (locPerm.status.isGranted) {
                        scope.launch { vm.currentLocation()?.let { (la, lo) -> recenter(la, lo) } }
                    } else {
                        locPerm.launchPermissionRequest()
                    }
                },
                onCancel = { editing = false },
                onSave = { lat, lon ->
                    vm.saveArea(
                        existingId = editingId,
                        existingCreatedUtc = editingCreated,
                        label = label,
                        lat = lat,
                        lon = lon,
                        radiusKm = radiusKm.toInt().toDouble(),
                        approachKm = approachKm.toInt().toDouble(),
                    )
                    editing = false
                },
            )
        }
    }

    deleteTarget?.let { area ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.alerts_delete_title)) },
            text = { Text(stringResource(R.string.alerts_delete_confirm, area.label)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(area.id); deleteTarget = null }) {
                    Text(stringResource(R.string.alerts_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Draws the area's two rings directly in screen space, centred on the map
 * viewport (which is exactly where the centre crosshair sits). Radius in pixels
 * is derived from the Web-Mercator ground resolution at the current centre
 * latitude + (possibly fractional) zoom, so the circle is geographically
 * accurate while never suffering the broken-fill / stray-line artifacts that
 * osmdroid's geographic [org.osmdroid.views.overlay.Polygon] produces for large
 * circles or circles whose centre is near the viewport edge.
 */
private class RingOverlay : Overlay() {
    @Volatile var radiusMeters: Double = 10_000.0
    @Volatile var approachMeters: Double = 0.0

    private val innerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AColor.argb(40, 190, 30, 45)
    }
    private val innerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = AColor.rgb(190, 30, 45)
    }
    private val outerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AColor.argb(28, 255, 184, 28)
    }
    private val outerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = AColor.rgb(255, 184, 28)
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val w = mapView.width
        val h = mapView.height
        if (w == 0 || h == 0) return
        val cx = w / 2f
        val cy = h / 2f
        // Web-Mercator metres-per-pixel at the centre latitude and current zoom.
        val lat = mapView.mapCenter.latitude
        val mpp = 156543.03392804097 * cos(Math.toRadians(lat)) / 2.0.pow(mapView.zoomLevelDouble)
        if (mpp <= 0.0 || mpp.isNaN()) return

        if (approachMeters > 0.0) {
            val ro = ((radiusMeters + approachMeters) / mpp).toFloat()
            canvas.drawCircle(cx, cy, ro, outerFill)
            canvas.drawCircle(cx, cy, ro, outerStroke)
        }
        val ri = (radiusMeters / mpp).toFloat()
        canvas.drawCircle(cx, cy, ri, innerFill)
        canvas.drawCircle(cx, cy, ri, innerStroke)
    }
}

@Composable
private fun NoticeCard(message: String, action: String, onAction: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun AreaCard(
    area: WatchArea,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🎯 ${area.label}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = area.enabled, onCheckedChange = onToggle)
            }
            Divider(Modifier.padding(vertical = 6.dp))
            Text(
                stringResource(R.string.alerts_ring_summary, area.radiusKm.toInt(), area.approachKm.toInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "lat ${"%.4f".format(area.lat)}, lon ${"%.4f".format(area.lon)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.alerts_edit))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.alerts_delete))
                }
            }
        }
    }
}

@Composable
private fun AreaEditor(
    label: String,
    onLabel: (String) -> Unit,
    radiusKm: Float,
    onRadius: (Float) -> Unit,
    approachKm: Float,
    onApproach: (Float) -> Unit,
    initialLat: Double,
    initialLon: Double,
    recenterTo: Pair<Double, Double>?,
    onUseMyLocation: ((Double, Double) -> Unit) -> Unit,
    onCancel: () -> Unit,
    onSave: (Double, Double) -> Unit,
) {
    val ctx = LocalContext.current

    // The rings are drawn in SCREEN space (a plain canvas circle centred on the
    // viewport) rather than as geographic polygons. osmdroid's Polygon fill +
    // outline render with broken wedges / stray radial lines when the circle is
    // large or its centre sits near the viewport edge during a pan/zoom; a
    // screen-space circle is always glued to the centre crosshair and renders
    // cleanly at every zoom level. See RingOverlay below.
    val rings = remember { RingOverlay() }

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(11.0)
            controller.setCenter(GeoPoint(initialLat, initialLon))
            overlays.add(rings)
        }
    }

    // Push the current slider values into the overlay and repaint. osmdroid
    // repaints overlays automatically on pan/zoom, so the rings track the
    // centre with no per-frame geometry recompute.
    LaunchedEffect(radiusKm, approachKm) {
        rings.radiusMeters = radiusKm.toDouble() * 1000.0
        rings.approachMeters = if (approachKm <= 0f) 0.0 else approachKm.toDouble() * 1000.0
        mapView.invalidate()
    }
    // When the user's location arrives (new area) or "Use my location" is tapped,
    // glide the map onto it; the rings repaint automatically around the new centre.
    LaunchedEffect(recenterTo) {
        recenterTo?.let { (la, lo) ->
            mapView.controller.animateTo(GeoPoint(la, lo))
            mapView.controller.setZoom(12.0)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.alerts_editor_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.alerts_editor_hint), style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value = label,
                onValueChange = onLabel,
                label = { Text(stringResource(R.string.alerts_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            OutlinedButton(
                onClick = {
                    onUseMyLocation { la, lo ->
                        mapView.controller.animateTo(GeoPoint(la, lo))
                        mapView.controller.setZoom(12.0)
                    }
                },
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.alerts_use_location))
            }

            Text(stringResource(R.string.alerts_radius_value, radiusKm.toInt()))
            Slider(value = radiusKm, onValueChange = onRadius, valueRange = 1f..50f, steps = 48)

            Text(
                if (approachKm <= 0f) stringResource(R.string.alerts_approach_off)
                else stringResource(R.string.alerts_approach_value, approachKm.toInt()),
            )
            Slider(value = approachKm, onValueChange = onApproach, valueRange = 0f..25f, steps = 24)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        val c = mapView.mapCenter
                        onSave(c.latitude, c.longitude)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.alerts_save))
                }
            }
        }
    }
}
