package com.phoenix.citizen.alerts

import com.phoenix.citizen.data.model.AreaAlertState
import com.phoenix.citizen.data.model.FiresNearResponse
import com.phoenix.citizen.data.model.NearbyFire
import com.phoenix.citizen.data.model.WatchArea

/**
 * Pure decision logic for area alerts — no Android, no I/O, fully unit-testable.
 *
 * Given a watch area, the fires the backend reports inside/approaching it, and
 * the prior per-area bookkeeping, decide:
 *   - whether to raise a notification (and its bilingual copy), and
 *   - the updated [AreaAlertState] to persist.
 *
 * Rules:
 *   - A detection is announced at most once per area (dedupe by detection_id).
 *   - "Inside" (a fire within the inner ring) outranks "approaching".
 *   - A short cooldown prevents an ongoing fire — which keeps producing new
 *     detection ids — from spamming the user; new detections during the cooldown
 *     are silently folded in (marked seen) so they don't re-fire afterwards.
 *   - "Moving closer" is added when the nearest fire is meaningfully closer than
 *     it was on the previous check.
 */
object AreaAlertLogic {

    const val NOTIFIED_ID_CAP = 300
    const val DEFAULT_COOLDOWN_MS = 15 * 60 * 1000L
    const val TREND_EPS_KM = 1.0

    const val CLASS_INSIDE = "inside"
    const val CLASS_APPROACHING = "approaching"

    data class PlannedNotification(
        val classification: String,   // CLASS_INSIDE | CLASS_APPROACHING
        val priorityHigh: Boolean,
        val title: String,
        val body: String,
        val dedupeKey: String,
    )

    data class Plan(
        val notifications: List<PlannedNotification>,
        val newState: AreaAlertState,
    )

    fun plan(
        area: WatchArea,
        resp: FiresNearResponse,
        prior: AreaAlertState,
        nowMs: Long,
        lang: String,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ): Plan {
        val notified = prior.notifiedIds.toHashSet()
        val inside = resp.inside
        val approaching = resp.approaching
        val allFires = inside + approaching
        val currentNearest = allFires.minOfOrNull { it.distanceKm }

        val newInside = inside.filter { it.detectionId !in notified }
        val newApproaching = approaching.filter { it.detectionId !in notified }
        val hasNew = newInside.isNotEmpty() || newApproaching.isNotEmpty()

        // Nothing new to announce — just refresh the nearest distance for trend.
        if (!hasNew) {
            return Plan(
                emptyList(),
                prior.copy(lastNearestKm = currentNearest ?: prior.lastNearestKm),
            )
        }

        val coveredIds = capIds(prior.notifiedIds, allFires.map { it.detectionId })
        val withinCooldown =
            prior.lastNotifiedAtMs != 0L && (nowMs - prior.lastNotifiedAtMs) < cooldownMs

        if (withinCooldown) {
            // Acknowledge the new detections silently so they don't fire later.
            return Plan(
                emptyList(),
                prior.copy(
                    notifiedIds = coveredIds,
                    lastNearestKm = currentNearest ?: prior.lastNearestKm,
                ),
            )
        }

        val priorNearest = prior.lastNearestKm
        val movingCloser = priorNearest != null && currentNearest != null &&
            currentNearest < priorNearest - TREND_EPS_KM

        val notif =
            if (newInside.isNotEmpty()) buildInside(area, inside.first(), lang)
            else buildApproaching(area, approaching.first(), movingCloser, lang)

        return Plan(
            listOf(notif),
            prior.copy(
                notifiedIds = coveredIds,
                lastNearestKm = currentNearest ?: prior.lastNearestKm,
                lastNotifiedAtMs = nowMs,
            ),
        )
    }

    /** Merge prior + current detection ids, de-duplicated, keeping the most recent CAP. */
    fun capIds(prior: List<Long>, current: List<Long>): List<Long> {
        val merged = LinkedHashSet<Long>(prior.size + current.size)
        merged.addAll(prior)
        merged.addAll(current)
        val list = merged.toList()
        return if (list.size <= NOTIFIED_ID_CAP) list else list.takeLast(NOTIFIED_ID_CAP)
    }

    private fun buildInside(area: WatchArea, nearest: NearbyFire, lang: String): PlannedNotification {
        val km = fmtKm(nearest.distanceKm)
        val title: String
        val body: String
        if (lang == "en") {
            title = "🔥 Fire in your area “${area.label}”"
            body = "A fire was detected ~$km km from the centre of “${area.label}”. " +
                "Open the map to see it, and follow official channels (Fire Brigade 115, Civil Protection)."
        } else {
            title = "🔥 Incendio nell'area «${area.label}»"
            body = "Rilevato un incendio a ~$km km dal centro di «${area.label}». " +
                "Apri la mappa per vederlo e segui i canali ufficiali (Vigili del Fuoco 115, Protezione Civile)."
        }
        return PlannedNotification(CLASS_INSIDE, true, title, body, "${area.id}:$CLASS_INSIDE")
    }

    private fun buildApproaching(
        area: WatchArea,
        nearest: NearbyFire,
        movingCloser: Boolean,
        lang: String,
    ): PlannedNotification {
        val km = fmtKm(nearest.distanceKm)
        val title: String
        val body: String
        if (lang == "en") {
            title = "⚠️ Fire approaching your area “${area.label}”"
            val closer = if (movingCloser) " It's moving closer." else ""
            body = "A fire was detected ~$km km ${nearest.compass} of “${area.label}”.$closer " +
                "Open the map to see it and follow official channels (Fire Brigade 115)."
        } else {
            title = "⚠️ Incendio vicino all'area «${area.label}»"
            val closer = if (movingCloser) " Si sta avvicinando." else ""
            body = "Rilevato un incendio a ~$km km a ${compassIt(nearest.compass)} da «${area.label}».$closer " +
                "Apri la mappa per vederlo e segui i canali ufficiali (Vigili del Fuoco 115)."
        }
        return PlannedNotification(CLASS_APPROACHING, true, title, body, "${area.id}:$CLASS_APPROACHING")
    }

    /** Distance: one decimal under 10 km, whole km above. */
    fun fmtKm(km: Double): String =
        if (km < 10.0) ((km * 10).toLong() / 10.0).toString() else km.toLong().toString()

    /** English 16-point compass → Italian abbreviation (E=Est stays, W=Ovest→O). */
    fun compassIt(c: String): String =
        c.map { ch -> if (ch == 'W') 'O' else ch }.joinToString("")
}
