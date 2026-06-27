package com.phoenix.citizen.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.citizen.data.model.SystemStatus
import java.util.Locale

/**
 * The 🟢🟡🔴 banner at the top of the map.
 *
 * Color coding:
 *   green   → not shown at all (system fully operational, no banner clutter)
 *   yellow  → orange banner: degraded mode, reports are saved + queued
 *   red     → red banner: PHOENIX backend offline, cached data only
 *
 * Message language follows the device locale (Italian → message_it, else en).
 */
@Composable
fun SystemStatusBanner(
    status: SystemStatus?,
    modifier: Modifier = Modifier,
) {
    if (status == null || status.state == "green") return

    val (bg, fg, icon) = when (status.state) {
        "yellow" -> Triple(Color(0xFFFFF4D6), Color(0xFF78350F), "⚠️")
        "red" -> Triple(Color(0xFFFEE2E2), Color(0xFF7F1D1D), "🔴")
        else -> Triple(Color(0xFFFFF4D6), Color(0xFF78350F), "ℹ️")
    }

    val msg = if (Locale.getDefault().language == "it") status.messageIt else status.messageEn

    Box(
        modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = icon,
                fontSize = 16.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = msg,
                color = fg,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
