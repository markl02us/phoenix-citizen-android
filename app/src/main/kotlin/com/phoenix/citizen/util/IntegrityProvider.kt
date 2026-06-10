package com.phoenix.citizen.util

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wrapper around Play Integrity API. This replaces CAPTCHA — every POST
 * carries an X-Integrity-Token header that the backend validates.
 *
 * TODO(Mark): set [CLOUD_PROJECT_NUMBER] from Google Cloud Console.
 * Until then the manager request is a no-op and this returns null,
 * which means the backend can either accept reports without attestation
 * (development) or reject them (production).
 */
class IntegrityProvider(private val context: Context) {

    suspend fun requestTokenOrNull(): String? {
        if (CLOUD_PROJECT_NUMBER <= 0L) return null

        return try {
            suspendCancellableCoroutine { cont ->
                val manager = IntegrityManagerFactory.create(context.applicationContext)
                val req = IntegrityTokenRequest.builder()
                    .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                    .build()
                manager.requestIntegrityToken(req)
                    .addOnSuccessListener { resp -> cont.resume(resp.token()) }
                    .addOnFailureListener { _ -> cont.resume(null) }
            }
        } catch (_: Throwable) { null }
    }

    companion object {
        // TODO(Mark): Replace with your Google Cloud project number
        // (Console → Project info → Project number). Until set, integrity is disabled.
        const val CLOUD_PROJECT_NUMBER: Long = 0L
    }
}
