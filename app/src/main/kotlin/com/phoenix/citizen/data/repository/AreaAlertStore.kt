package com.phoenix.citizen.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.phoenix.citizen.data.model.AreaAlertState
import com.phoenix.citizen.data.model.WatchArea
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.areaAlertDataStore by preferencesDataStore(name = "area_alerts_prefs")

/**
 * Device-local persistence for the user's watch areas (range rings) and the
 * per-area notification bookkeeping. Everything lives on-device — no account,
 * no server-side list to leak. Serialized as JSON blobs inside DataStore.
 */
class AreaAlertStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val AREAS = stringPreferencesKey("watch_areas")
        val STATES = stringPreferencesKey("area_states")
    }

    private val areaListSer = ListSerializer(WatchArea.serializer())
    private val stateListSer = ListSerializer(AreaAlertState.serializer())

    val areasFlow: Flow<List<WatchArea>> = context.areaAlertDataStore.data.map { prefs ->
        decodeAreas(prefs[Keys.AREAS])
    }

    private fun decodeAreas(raw: String?): List<WatchArea> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(areaListSer, raw) }.getOrDefault(emptyList())

    private fun decodeStates(raw: String?): List<AreaAlertState> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(stateListSer, raw) }.getOrDefault(emptyList())

    suspend fun getAreas(): List<WatchArea> = areasFlow.first()

    /** Insert or replace (matched by id). */
    suspend fun upsert(area: WatchArea) {
        context.areaAlertDataStore.edit { prefs ->
            val current = decodeAreas(prefs[Keys.AREAS]).filterNot { it.id == area.id }
            prefs[Keys.AREAS] = json.encodeToString(areaListSer, current + area)
        }
    }

    suspend fun delete(id: String) {
        context.areaAlertDataStore.edit { prefs ->
            val areas = decodeAreas(prefs[Keys.AREAS]).filterNot { it.id == id }
            prefs[Keys.AREAS] = json.encodeToString(areaListSer, areas)
            val states = decodeStates(prefs[Keys.STATES]).filterNot { it.areaId == id }
            prefs[Keys.STATES] = json.encodeToString(stateListSer, states)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        context.areaAlertDataStore.edit { prefs ->
            val areas = decodeAreas(prefs[Keys.AREAS]).map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            prefs[Keys.AREAS] = json.encodeToString(areaListSer, areas)
        }
    }

    suspend fun getState(areaId: String): AreaAlertState =
        decodeStates(context.areaAlertDataStore.data.first()[Keys.STATES])
            .firstOrNull { it.areaId == areaId }
            ?: AreaAlertState(areaId = areaId)

    suspend fun putState(state: AreaAlertState) {
        context.areaAlertDataStore.edit { prefs ->
            val others = decodeStates(prefs[Keys.STATES]).filterNot { it.areaId == state.areaId }
            prefs[Keys.STATES] = json.encodeToString(stateListSer, others + state)
        }
    }
}
