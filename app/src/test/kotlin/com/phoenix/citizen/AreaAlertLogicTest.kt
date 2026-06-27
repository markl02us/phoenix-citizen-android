package com.phoenix.citizen

import com.phoenix.citizen.alerts.AreaAlertLogic
import com.phoenix.citizen.data.model.AreaAlertState
import com.phoenix.citizen.data.model.FiresNearCounts
import com.phoenix.citizen.data.model.FiresNearResponse
import com.phoenix.citizen.data.model.NearbyFire
import com.phoenix.citizen.data.model.WatchArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaAlertLogicTest {

    private val area = WatchArea(
        id = "a1", label = "My farm", lat = 37.5, lon = 13.5,
        radiusKm = 10.0, approachKm = 10.0, enabled = true, createdUtc = 1L,
    )

    private fun fire(id: Long, dist: Double, compass: String = "N") = NearbyFire(
        detectionId = id, lat = 37.5, lon = 13.5, distanceKm = dist,
        bearingDeg = 0, compass = compass,
    )

    private fun resp(inside: List<NearbyFire> = emptyList(), approaching: List<NearbyFire> = emptyList()) =
        FiresNearResponse(
            counts = FiresNearCounts(inside.size, approaching.size),
            inside = inside, approaching = approaching,
        )

    private fun emptyState() = AreaAlertState(areaId = area.id)

    @Test fun newInsideFire_emitsInsideNotification() {
        val p = AreaAlertLogic.plan(area, resp(inside = listOf(fire(1, 5.0))), emptyState(), 1_000L, "en")
        assertEquals(1, p.notifications.size)
        assertEquals(AreaAlertLogic.CLASS_INSIDE, p.notifications[0].classification)
        assertTrue(p.newState.notifiedIds.contains(1L))
        assertEquals(1_000L, p.newState.lastNotifiedAtMs)
        assertTrue(p.notifications[0].title.contains("My farm"))
    }

    @Test fun alreadyNotifiedFire_emitsNothing() {
        val prior = AreaAlertState(areaId = area.id, notifiedIds = listOf(1L), lastNotifiedAtMs = 500L)
        val p = AreaAlertLogic.plan(area, resp(inside = listOf(fire(1, 5.0))), prior, 10_000_000L, "en")
        assertTrue(p.notifications.isEmpty())
    }

    @Test fun approachingOnly_emitsApproaching() {
        val p = AreaAlertLogic.plan(area, resp(approaching = listOf(fire(2, 15.0, "E"))), emptyState(), 1_000L, "en")
        assertEquals(1, p.notifications.size)
        assertEquals(AreaAlertLogic.CLASS_APPROACHING, p.notifications[0].classification)
        assertTrue(p.notifications[0].body.contains("E"))
    }

    @Test fun insidePriorityOverApproaching() {
        val p = AreaAlertLogic.plan(
            area,
            resp(inside = listOf(fire(1, 5.0)), approaching = listOf(fire(2, 15.0, "E"))),
            emptyState(), 1_000L, "en",
        )
        assertEquals(1, p.notifications.size)
        assertEquals(AreaAlertLogic.CLASS_INSIDE, p.notifications[0].classification)
        // Both detections are acknowledged so the approaching one won't re-fire.
        assertTrue(p.newState.notifiedIds.containsAll(listOf(1L, 2L)))
    }

    @Test fun cooldown_foldsNewFireSilently() {
        val prior = AreaAlertState(areaId = area.id, notifiedIds = listOf(1L), lastNotifiedAtMs = 1_000L)
        // New fire id=2 arrives 1 min later — within the 15-min cooldown.
        val p = AreaAlertLogic.plan(area, resp(inside = listOf(fire(2, 4.0))), prior, 1_000L + 60_000L, "en")
        assertTrue(p.notifications.isEmpty())
        assertTrue(p.newState.notifiedIds.contains(2L)) // folded in, won't re-fire later
    }

    @Test fun afterCooldown_newFireFiresAgain() {
        val prior = AreaAlertState(areaId = area.id, notifiedIds = listOf(1L), lastNotifiedAtMs = 1_000L)
        val later = 1_000L + AreaAlertLogic.DEFAULT_COOLDOWN_MS + 1
        val p = AreaAlertLogic.plan(area, resp(inside = listOf(fire(2, 4.0))), prior, later, "en")
        assertEquals(1, p.notifications.size)
    }

    @Test fun movingCloser_wordingAppears() {
        val prior = AreaAlertState(areaId = area.id, lastNearestKm = 18.0, lastNotifiedAtMs = 0L)
        val p = AreaAlertLogic.plan(area, resp(approaching = listOf(fire(3, 12.0, "E"))), prior, 5_000L, "en")
        assertEquals(1, p.notifications.size)
        assertTrue(p.notifications[0].body.contains("moving closer"))
    }

    @Test fun noFires_noNotification_updatesNearest() {
        val prior = AreaAlertState(areaId = area.id, lastNearestKm = 5.0, lastNotifiedAtMs = 0L)
        val p = AreaAlertLogic.plan(area, resp(), prior, 5_000L, "en")
        assertTrue(p.notifications.isEmpty())
    }

    @Test fun capIds_trimsToCap() {
        val prior = (1..AreaAlertLogic.NOTIFIED_ID_CAP + 50).map { it.toLong() }
        val out = AreaAlertLogic.capIds(prior, listOf(999_999L))
        assertEquals(AreaAlertLogic.NOTIFIED_ID_CAP, out.size)
        assertTrue(out.contains(999_999L)) // newest kept
        assertFalse(out.contains(1L))      // oldest dropped
    }

    @Test fun fmtKm_formatsCorrectly() {
        assertEquals("4.5", AreaAlertLogic.fmtKm(4.53))
        assertEquals("15", AreaAlertLogic.fmtKm(15.4))
    }

    @Test fun compassIt_mapsWestToO() {
        assertEquals("NO", AreaAlertLogic.compassIt("NW"))
        assertEquals("OSO", AreaAlertLogic.compassIt("WSW"))
        assertEquals("NE", AreaAlertLogic.compassIt("NE"))
    }

    @Test fun italianInsideCopy_isItalian() {
        val p = AreaAlertLogic.plan(area, resp(inside = listOf(fire(1, 5.0))), emptyState(), 1_000L, "it")
        assertTrue(p.notifications[0].body.contains("Vigili del Fuoco"))
    }
}
