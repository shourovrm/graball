package com.graball.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hosts-based ad/tracker blocklist (StevenBlack hosts, assets/adhosts.txt: one lowercase
 * domain per line). Loaded once off the UI/network thread; [isBlocked] is a plain sync
 * HashSet lookup so it never stalls shouldInterceptRequest.
 */
object AdBlocker {
    @Volatile private var ready = false
    private val blocked = HashSet<String>()

    suspend fun ensureLoaded(context: Context) {
        if (ready) return
        withContext(Dispatchers.IO) {
            if (ready) return@withContext
            runCatching {
                context.assets.open("adhosts.txt").bufferedReader().forEachLine { line ->
                    val h = line.trim()
                    if (h.isNotEmpty()) blocked.add(h)
                }
            }
            ready = true
        }
    }

    /** Exact host or any parent domain (walk labels right-to-left) is blocked. */
    fun isBlocked(host: String?): Boolean {
        if (!ready || host.isNullOrEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in blocked) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
