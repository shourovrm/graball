package com.graball.download

import android.content.Context
import android.content.Intent
import com.graball.GraballApp
import com.graball.resolve.ResolvedItem
import com.graball.resolve.Variant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Enqueue {
    /** Inserts QUEUED rows then starts the download service. Insert-then-start ordering
     * guarantees the service's first nextQueued() poll actually sees these rows. */
    fun enqueue(
        context: Context,
        selection: List<Pair<ResolvedItem, Variant>>,
        cookieDomain: String? = null,
    ) {
        val app = context.applicationContext as GraballApp
        val dao = GraballDb.getInstance(app).downloadDao()
        app.appScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
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
                        createdAt = now,
                    )
                )
            }
            app.startForegroundService(Intent(app, DownloadService::class.java))
        }
    }
}
