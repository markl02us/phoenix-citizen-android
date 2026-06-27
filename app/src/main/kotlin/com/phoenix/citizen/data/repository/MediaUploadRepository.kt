package com.phoenix.citizen.data.repository

import android.content.Context
import android.net.Uri
import com.phoenix.citizen.data.api.NetworkModule
import com.phoenix.citizen.data.model.CitizenUploadIntentRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Two-step direct-to-R2 upload for citizen photo + voice attachments.
 *
 *   1. POST /api/citizen_upload_intent  → { key, upload_url, max_bytes }
 *   2. PUT  <upload_url>  body=<bytes>  content-type=<mime>
 *
 * The Worker keeps the bytes only in R2 — never buffered into D1 or KV. The
 * returned R2 key is what we send back in the `media_keys` field of the
 * citizen_report POST.
 *
 * Returns the R2 key on success, null on any failure (the report still goes
 * through without media attached — the user keeps the photo locally so they
 * can retry).
 */
class MediaUploadRepository(private val ctx: Context) {

    /** Upload a photo from a content URI. Returns R2 key or null. */
    suspend fun uploadPhoto(uri: Uri, mime: String = "image/jpeg"): String? =
        upload(uri = uri, kind = "photo", mime = mime)

    /** Upload a voice memo from a content URI. Returns R2 key or null. */
    suspend fun uploadVoice(uri: Uri, mime: String = "audio/m4a"): String? =
        upload(uri = uri, kind = "voice", mime = mime)

    private suspend fun upload(uri: Uri, kind: String, mime: String): String? {
        try {
            // Step 1: get presigned URL.
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val intent = NetworkModule.apiV2.uploadIntent(
                CitizenUploadIntentRequest(
                    kind = kind,
                    mimeType = mime,
                    sizeBytesHint = bytes.size.toLong(),
                )
            )
            if (!intent.isSuccessful) return null
            val info = intent.body() ?: return null
            if (bytes.size > info.maxBytes) return null

            // Step 2: PUT bytes to the presigned URL.
            val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
            val put = NetworkModule.apiV2.uploadMediaBytes(
                presignedUrl = info.uploadUrl,
                contentType = mime,
                bytes = body,
            )
            return if (put.isSuccessful) info.key else null
        } catch (_: Throwable) {
            return null
        }
    }
}
