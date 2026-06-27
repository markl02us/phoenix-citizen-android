package com.phoenix.citizen.ui.screens

import android.graphics.Color as AColor
import android.view.MotionEvent
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoenix.citizen.R
import com.phoenix.citizen.data.model.Detection
import com.phoenix.citizen.ui.components.SystemStatusBanner
import com.phoenix.citizen.util.TimeUtils
import com.phoenix.citizen.viewmodel.CitizenConfirmViewModel
import com.phoenix.citizen.viewmodel.MapViewModel
import com.phoenix.citizen.viewmodel.SystemStatusViewModel
import java.util.Locale
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

// Sicily center
private const val SICILY_LAT = 37.5
private const val SICILY_LON = 14.0
private const val INIT_ZOOM = 8.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onReportHere: (Double, Double) -> Unit,
    vm: MapViewModel = viewModel(),
    statusVm: SystemStatusViewModel = viewModel(),
    confirmVm: CitizenConfirmViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val systemStatus by statusVm.status.collectAsState()
    val confirmState by confirmVm.state.collectAsState()
    var sheetDetection by remember { mutableStateOf<Detection?>(null) }
    var longPressed by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // Keep a stable reference to the MapView so updates can target it.
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(INIT_ZOOM)
            controller.setCenter(GeoPoint(SICILY_LAT, SICILY_LON))
        }
    }

    // Long-press → bottom sheet
    DisposableEffect(mapView) {
        var downX = 0f; var downY = 0f
        var downAt = 0L
        val LP_MS = 500L
        val LP_PX = 28f
        mapView.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; downAt = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX; val dy = ev.y - downY
                    if (System.currentTimeMillis() - downAt >= LP_MS &&
                        kotlin.math.hypot(dx.toDouble(), dy.toDouble()) < LP_PX
                    ) {
                        val proj = mapView.projection
                        val gp = proj.fromPixels(ev.x.toInt(), ev.y.toInt()) as GeoPoint
                        longPressed = gp.latitude to gp.longitude
                    }
                }
            }
            false  // don't consume — let map still handle pan/zoom
        }
        onDispose { mapView.setOnTouchListener(null) }
    }

    // Viewport-change → vm.loadForViewport (idle, debounced via "scroll ended" listener)
    DisposableEffect(mapView) {
        val listener = object : MapListener {
            private var lastFire = 0L
            private fun fire() {
                val now = System.currentTimeMillis()
                if (now - lastFire < 400) return
                lastFire = now
                val bb = mapView.boundingBox
                vm.loadForViewport(
                    south = bb.latSouth,
                    west = bb.lonWest,
                    north = bb.latNorth,
                    east = bb.lonEast,
                )
            }
            override fun onScroll(event: ScrollEvent?): Boolean { fire(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { fire(); return false }
        }
        mapView.addMapListener(listener)
        // initial fetch
        mapView.post { listener.onScroll(null) }
        onDispose { mapView.removeMapListener(listener) }
    }

    // Detections → markers. Citizen reports get a much larger, distinct icon.
    LaunchedEffect(state.detections) {
        val toRemove = mapView.overlays.filterIsInstance<Marker>().filter { it.relatedObject is Detection }
        mapView.overlays.removeAll(toRemove)

        // Render satellite/press/social markers first so citizen reports paint on top
        val (citizen, rest) = state.detections.partition { it.source.lowercase() == "citizen_report" }

        rest.forEach { d ->
            val m = Marker(mapView).apply {
                position = GeoPoint(d.lat, d.lng)
                title = d.source
                subDescription = d.timestamp?.let { TimeUtils.formatLocal(it) } ?: ""
                relatedObject = d
                icon = android.graphics.drawable.BitmapDrawable(ctx.resources, dotBitmap(d.markerColor(), size = 32))
                setOnMarkerClickListener { _, _ -> sheetDetection = d; true }
            }
            mapView.overlays.add(m)
        }
        citizen.forEach { d ->
            val m = Marker(mapView).apply {
                position = GeoPoint(d.lat, d.lng)
                title = ctx.getString(R.string.citizen_marker_title)
                subDescription = d.timestamp?.let { TimeUtils.formatLocal(it) } ?: ""
                relatedObject = d
                icon = android.graphics.drawable.BitmapDrawable(ctx.resources, citizenAlertBitmap())
                setOnMarkerClickListener { _, _ -> sheetDetection = d; true }
            }
            mapView.overlays.add(m)
        }
        mapView.invalidate()
    }

    Column(Modifier.fillMaxSize()) {
      SystemStatusBanner(status = systemStatus)
      Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )

        if (state.loading) {
            Text(
                text = stringResource(R.string.map_loading),
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium
            )
        }

        longPressed?.let { (lat, lon) ->
            ModalBottomSheet(
                onDismissRequest = { longPressed = null },
                sheetState = sheetState
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("lat=${"%.5f".format(lat)}, lon=${"%.5f".format(lon)}")
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch { sheetState.hide() }
                            longPressed = null
                            onReportHere(lat, lon)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.map_report_here))
                    }
                }
            }
        }

        sheetDetection?.let { d ->
            val isCitizen = d.source.lowercase() == "citizen_report"
            ModalBottomSheet(onDismissRequest = { sheetDetection = null }) {
                Column(Modifier.padding(16.dp)) {
                    if (isCitizen) {
                        Text(
                            text = "🔥 " + stringResource(R.string.citizen_marker_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.citizen_marker_subtitle), style = MaterialTheme.typography.bodyMedium)
                        // Multi-reporter counter — increments after confirm.
                        val voterCount = d.voterCount ?: 1
                        if (voterCount > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "👥  $voterCount " +
                                    (if (Locale.getDefault().language == "it") "segnalazioni in questa zona"
                                     else "reports in this area"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        // "I see this too" button — calls /api/citizen_confirm.
                        d.id?.let { reportId ->
                            Button(
                                onClick = { confirmVm.confirm(reportId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = if (Locale.getDefault().language == "it")
                                        "👁  Anch'io vedo questo"
                                    else
                                        "👁  I see this too",
                                )
                            }
                        }
                        confirmState.lastMessage?.let { msg ->
                            Spacer(Modifier.height(8.dp))
                            Text(msg, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Text(d.source, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                    }
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
}

private fun dotBitmap(@androidx.annotation.ColorInt color: Int, size: Int = 36): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, fill)
    val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
        this.color = AColor.WHITE
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, ring)
    return bmp
}

/**
 * Citizen-report marker — ~3× the diameter of a satellite marker, Sicilian-red filled,
 * gold outer halo + white inner ring, flame glyph in the middle. Designed to dominate
 * the map: a citizen on the ground saw fire here, so it must be unmissable.
 */
private fun citizenAlertBitmap(): android.graphics.Bitmap {
    val size = 96
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val c = size / 2f

    // Outer gold halo (soft)
    val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.argb(140, 255, 184, 28)  // Sicilian gold, semi-transparent
    }
    canvas.drawCircle(c, c, c - 2f, halo)

    // Gold ring
    val goldRing = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 5f
        color = AColor.rgb(255, 184, 28)
    }
    canvas.drawCircle(c, c, c - 8f, goldRing)

    // Red filled core
    val red = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.rgb(190, 30, 45)
    }
    canvas.drawCircle(c, c, c - 14f, red)

    // White inner ring for contrast
    val whiteRing = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f
        color = AColor.WHITE
    }
    canvas.drawCircle(c, c, c - 14f, whiteRing)

    // Flame emoji centered
    val text = "\uD83D\uDD25"  // 🔥
    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * 0.5f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val fm = tp.fontMetrics
    canvas.drawText(text, c, c - (fm.ascent + fm.descent) / 2f, tp)

    return bmp
}

private fun Detection.markerColor(): Int = when (source.lowercase()) {
    // ADRIZ / PHOENIX-native detectors — Sicilian red
    "wind_diff", "fci_l1c", "subpixel_v1_alpha", "dozier_v1_alpha", "s2_swir", "adr" -> AColor.rgb(190, 30, 45)
    // FIRMS family — orange
    "firms_viirs_noaa20", "firms_viirs_noaa21", "firms_viirs_snpp", "firms_modis_nrt" -> AColor.rgb(253, 126, 20)
    // EUMETSAT family — gold
    "mtg_af_l2", "slstr_frp_s3a", "slstr_frp_s3b", "metimage", "fci_rss", "mtg_irs" -> AColor.rgb(255, 184, 28)
    // News/press — green
    "ansa_rss", "vigili_fuoco", "italian_news_rss" -> AColor.rgb(55, 178, 77)
    // Social — cyan
    "reddit", "mastodon" -> AColor.rgb(33, 158, 188)
    // Citizen reports — blue
    "citizen_report" -> AColor.rgb(25, 113, 194)
    // Voted/event-level — violet
    "voted", "voted_event" -> AColor.rgb(121, 80, 242)
    else -> AColor.rgb(190, 30, 45)
}
