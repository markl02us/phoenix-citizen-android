package com.phoenix.citizen.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.phoenix.citizen.R
import com.phoenix.citizen.data.model.Detection
import com.phoenix.citizen.util.TimeUtils
import com.phoenix.citizen.viewmodel.MapViewModel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val SICILY_CENTER = LatLng(37.5, 14.0) // default centered on Sicily for PHOENIX context

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onReportHere: (Double, Double) -> Unit,
    vm: MapViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(SICILY_CENTER, 8f)
    }
    var sheetDetection by remember { mutableStateOf<Detection?>(null) }
    var longPressed by remember { mutableStateOf<LatLng?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Refetch on idle viewport change
    LaunchedEffect(camera) {
        snapshotFlow { camera.isMoving }
            .distinctUntilChanged()
            .debounce(400)
            .collect { moving ->
                if (!moving) {
                    val bounds = camera.projection?.visibleRegion?.latLngBounds ?: return@collect
                    vm.loadForViewport(
                        south = bounds.southwest.latitude,
                        west = bounds.southwest.longitude,
                        north = bounds.northeast.latitude,
                        east = bounds.northeast.longitude
                    )
                }
            }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = false),
            onMapLongClick = { latLng -> longPressed = latLng }
        ) {
            state.detections.forEach { d ->
                Marker(
                    state = MarkerState(LatLng(d.lat, d.lng)),
                    title = d.source,
                    snippet = d.timestamp?.let { TimeUtils.formatLocal(it) } ?: "",
                    icon = BitmapDescriptorFactory.defaultMarker(d.markerHue()),
                    onClick = {
                        sheetDetection = d
                        true
                    }
                )
            }
        }

        if (state.loading) {
            Text(
                text = stringResource(R.string.map_loading),
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium
            )
        }

        longPressed?.let { ll ->
            ModalBottomSheet(
                onDismissRequest = { longPressed = null },
                sheetState = sheetState
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("lat=${"%.5f".format(ll.latitude)}, lon=${"%.5f".format(ll.longitude)}")
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val tgt = ll
                            scope.launch { sheetState.hide() }
                            longPressed = null
                            onReportHere(tgt.latitude, tgt.longitude)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.map_report_here))
                    }
                }
            }
        }

        sheetDetection?.let { d ->
            ModalBottomSheet(onDismissRequest = { sheetDetection = null }) {
                Column(Modifier.padding(16.dp)) {
                    Text(d.source, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    val tsLabel = d.timestamp?.let { TimeUtils.formatLocal(it) } ?: "—"
                    Text("${d.source.uppercase()} · $tsLabel")
                    d.frpMw?.let { Text("FRP: ${"%.1f".format(it)} MW") }
                    d.confidence?.let { Text("Confidence: ${"%.0f".format(it * 100)}%") }
                    d.fireTempC?.let { Text("Fire temp: ${"%.0f".format(it)} °C") }
                    d.uncertaintyRadiusKm?.let { Text("Uncertainty: ${"%.1f".format(it)} km") }
                    Spacer(Modifier.height(8.dp))
                    Text("lat=${"%.5f".format(d.lat)}, lng=${"%.5f".format(d.lng)}")
                }
            }
        }
    }
}

private fun Detection.markerHue(): Float = when (source.lowercase()) {
    // PHOENIX-native detectors
    "wind_diff", "fci_l1c", "subpixel_v1_alpha", "dozier_v1_alpha", "s2_swir", "adr" ->
        BitmapDescriptorFactory.HUE_RED
    // FIRMS family
    "firms_viirs_noaa20", "firms_viirs_noaa21", "firms_viirs_snpp", "firms_modis_nrt" ->
        BitmapDescriptorFactory.HUE_ORANGE
    // EUMETSAT family
    "mtg_af_l2", "slstr_frp_s3a", "slstr_frp_s3b", "metimage", "fci_rss", "mtg_irs" ->
        BitmapDescriptorFactory.HUE_YELLOW
    // News/press
    "ansa_rss", "vigili_fuoco", "italian_news_rss" ->
        BitmapDescriptorFactory.HUE_GREEN
    // Social (teal)
    "reddit", "mastodon" ->
        BitmapDescriptorFactory.HUE_CYAN
    // Citizen reports
    "citizen_report" ->
        BitmapDescriptorFactory.HUE_BLUE
    // Voted/event-level
    "voted", "voted_event" ->
        BitmapDescriptorFactory.HUE_VIOLET
    else -> BitmapDescriptorFactory.HUE_ROSE
}
