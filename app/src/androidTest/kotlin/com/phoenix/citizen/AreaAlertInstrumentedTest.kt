package com.phoenix.citizen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phoenix.citizen.alerts.AreaAlertChecker
import com.phoenix.citizen.data.model.WatchArea
import com.phoenix.citizen.data.repository.AreaAlertStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * On-device end-to-end tests: real DataStore persistence + the real backend
 * /api/fires_near over the network. These run on an emulator/device.
 */
@RunWith(AndroidJUnit4::class)
class AreaAlertInstrumentedTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun store_persistsAndDeletesArea() = runBlocking {
        val store = AreaAlertStore(ctx)
        val id = UUID.randomUUID().toString()
        val area = WatchArea(id, "Test farm", 37.67, 13.59, 5.0, 10.0, true, 1L)
        store.upsert(area)
        try {
            val loaded = store.getAreas().firstOrNull { it.id == id }
            assertNotNull("area persisted", loaded)
            assertEquals("Test farm", loaded!!.label)
            assertEquals(5.0, loaded.radiusKm, 0.001)

            store.setEnabled(id, false)
            assertEquals(false, store.getAreas().first { it.id == id }.enabled)

            // State round-trips.
            val st = store.getState(id).copy(notifiedIds = listOf(1L, 2L), lastNearestKm = 3.0)
            store.putState(st)
            val back = store.getState(id)
            assertEquals(listOf(1L, 2L), back.notifiedIds)
        } finally {
            store.delete(id)
            assertTrue(store.getAreas().none { it.id == id })
        }
    }

    /**
     * Real network: centre an area on a location with known live activity and
     * confirm the checker reaches the backend and returns counts. We assert it
     * RUNS end-to-end (returns a result for the area) rather than a specific
     * fire count, since live data changes.
     */
    @Test
    fun checker_runsAgainstLiveBackend() = runBlocking {
        val store = AreaAlertStore(ctx)
        val id = UUID.randomUUID().toString()
        store.upsert(WatchArea(id, "Live test", 37.67, 13.59, 3.0, 15.0, true, 1L))
        try {
            val summary = AreaAlertChecker(ctx).runCheck(force = true)
            assertNotNull(summary)
            assertTrue("checker ran at least our area", summary.ranAreas >= 1)
            val result = summary.results.firstOrNull { it.label == "Live test" }
            assertNotNull("got a result for our area (network reached)", result)
            assertTrue("non-negative counts", result!!.inside >= 0 && result.approaching >= 0)
        } finally {
            store.delete(id)
        }
    }
}
