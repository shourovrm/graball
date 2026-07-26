package com.graball.download

import android.content.Context
import android.content.Intent
import com.graball.GraballApp
import com.graball.resolve.ResolvedItem
import com.graball.resolve.Variant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Enqueue {
    /** Inserts QUEUED rows then starts the download service. Insert-then-start ordering
     * guarantees the service's first nextQueued() poll actually sees these rows. */
    /** [onDone] runs on the main thread AFTER the service start — callers that finish() their
     * activity must do it there, or the API 31+ background FGS-start ban crashes the process. */
    fun enqueue(
        context: Context,
        selection: List<Pair<ResolvedItem, Variant>>,
        cookieDomain: String? = null,
        onDone: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext as GraballApp
        val dao = GraballDb.getInstance(app).downloadDao()
        app.appScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val engineVer = runCatching {
                com.yausername.youtubedl_android.YoutubeDL.getInstance().version(app)
            }.getOrNull()
            selection.forEach { (item, variant) ->
                dao.insert(
                    DownloadEntity(
                        url = item.sourceUrl,
                        title = item.title,
                        thumbnail = item.thumbnail,
                        formatId = variant.formatId,
                        needsMux = variant.needsMux,
                        ext = variant.ext,
                        cookieDomain = cookieDomain,
                        engineVersion = engineVer,
                        createdAt = now,
                    )
                )
            }
            app.startForegroundService(Intent(app, DownloadService::class.java))
            onDone?.let { cb -> withContext(Dispatchers.Main) { cb() } }
        }
    }
}
