package com.graball.engine

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.graball.download.GraballDb
import com.yausername.youtubedl_android.YoutubeDL
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// engine update state cached across process restarts -- avoids hammering GitHub API
private val Context.engineUpdateStore by preferencesDataStore(name = "engine_update_check")
private val LAST_CHECK_MILLIS = longPreferencesKey("last_check_millis")
private val LAST_LATEST = stringPreferencesKey("last_latest")
private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
private const val RELEASES_URL = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"

/**
 * yt-dlp binary update, explicit-consent only (F-Droid policy, PROMPT.md:5).
 * Verified against youtubedl-android 0.18.1 (io.github.junkfood02 fork) via decompiled aar:
 * YoutubeDL.getInstance().version(context): String?, .updateYoutubeDL(context, UpdateChannel): UpdateStatus,
 * channel constants are YoutubeDL.UpdateChannel._STABLE / ._NIGHTLY / ._MASTER (underscore is the real API,
 * not a typo -- confirmed in bytecode, matches upstream README samples).
 */
object EngineUpdater {

    fun installedVersion(context: Context): String? =
        try {
            YoutubeDL.getInstance().version(context)
        } catch (e: Exception) {
            null
        }

    /** One-shot network call, no cache. Null on any failure -- caller just won't show a banner. */
    suspend fun latestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "graball-android") // GitHub API 403s without one
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            // never log response bodies -- untrusted remote content
            Json.parseToJsonElement(body).jsonObject["tag_name"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    /** Cached latest tag, at most one network call per 24h unless [force]. */
    suspend fun checkLatest(context: Context, force: Boolean = false): String? {
        val prefs = context.engineUpdateStore.data.first()
        val cached = prefs[LAST_LATEST]
        val lastCheck = prefs[LAST_CHECK_MILLIS] ?: 0L
        val now = System.currentTimeMillis()
        if (!force && cached != null && now - lastCheck < CHECK_INTERVAL_MS) return cached

        val fresh = latestVersion()
        if (fresh != null) {
            context.engineUpdateStore.edit {
                it[LAST_CHECK_MILLIS] = now
                it[LAST_LATEST] = fresh
            }
        }
        return fresh ?: cached // network hiccup: fall back to stale cache rather than nothing
    }

    suspend fun updateAvailable(context: Context, force: Boolean = false): Boolean {
        val latest = checkLatest(context, force)
        val installed = installedVersion(context)
        return latest != null && installed != null && latest != installed
    }

    /** Explicit user action only -- caller shows consent dialog before calling this. */
    suspend fun update(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val active = GraballDb.getInstance(context).downloadDao().countActive()
        if (active > 0) return@withContext Result.failure(IllegalStateException("Downloads running"))
        try {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            Result.success(installedVersion(context) ?: "unknown")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
