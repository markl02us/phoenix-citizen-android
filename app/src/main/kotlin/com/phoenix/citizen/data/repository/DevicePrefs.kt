package com.phoenix.citizen.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.phoenixDataStore by preferencesDataStore(name = "phoenix_prefs")

/**
 * Wraps DataStore for the few pieces of device-scoped state we need:
 *  - stable device_hash (generated on first launch — NOT ANDROID_ID)
 *  - language preference
 *  - push-notifications toggle
 */
class DevicePrefs(private val context: Context) {

    private object Keys {
        val DEVICE_HASH = stringPreferencesKey("device_hash")
        val LANGUAGE = stringPreferencesKey("language") // "system" | "en" | "it"
        val PUSH_ENABLED = booleanPreferencesKey("push_enabled")
        val REPUTATION_TIER = stringPreferencesKey("reputation_tier")
    }

    val deviceHashFlow: Flow<String> = context.phoenixDataStore.data.map { prefs ->
        prefs[Keys.DEVICE_HASH] ?: ""
    }

    val languageFlow: Flow<String> = context.phoenixDataStore.data.map { prefs ->
        prefs[Keys.LANGUAGE] ?: "system"
    }

    val pushEnabledFlow: Flow<Boolean> = context.phoenixDataStore.data.map { prefs ->
        prefs[Keys.PUSH_ENABLED] ?: true
    }

    val reputationTierFlow: Flow<String> = context.phoenixDataStore.data.map { prefs ->
        prefs[Keys.REPUTATION_TIER] ?: "new"
    }

    /**
     * Returns the stable device hash, generating one (random UUIDv4) on first call.
     * We DO NOT use ANDROID_ID — it's deprecated for privacy reasons and tied to OS reinstalls.
     */
    suspend fun getOrCreateDeviceHash(): String {
        val existing = deviceHashFlow.first()
        if (existing.isNotBlank()) return existing
        val fresh = UUID.randomUUID().toString().replace("-", "")
        context.phoenixDataStore.edit { it[Keys.DEVICE_HASH] = fresh }
        return fresh
    }

    suspend fun setLanguage(lang: String) {
        context.phoenixDataStore.edit { it[Keys.LANGUAGE] = lang }
    }

    suspend fun setPushEnabled(enabled: Boolean) {
        context.phoenixDataStore.edit { it[Keys.PUSH_ENABLED] = enabled }
    }

    suspend fun setReputationTier(tier: String) {
        context.phoenixDataStore.edit { it[Keys.REPUTATION_TIER] = tier }
    }
}
