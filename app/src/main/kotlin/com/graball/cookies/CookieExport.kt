package com.graball.cookies

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File
import java.util.UUID

/**
 * Domain-scoped Netscape cookies.txt export for a single yt-dlp call.
 *
 * SECURITY (see CLAUDE.md "Secrets & confidentiality"):
 * - source is only this app's own WebView CookieManager;
 * - file lives in filesDir/cookies (app-private), random name, deleted in `finally`;
 * - cookie names/values are never logged, never put in an exception message, never in the DB.
 */
object CookieExport {

    /** True if the WebView jar holds anything for this URL's host. Value is never inspected. */
    fun haveCookiesFor(url: String): Boolean = cookieHeader(url) != null

    /**
     * Exports cookies for [targetUrl]'s domain, runs [block] with the file path (null = no
     * cookies stored), and always deletes the file before returning.
     */
    suspend fun <T> withCookieFile(
        context: Context,
        targetUrl: String,
        block: suspend (path: String?) -> T,
    ): T {
        val host = hostOf(targetUrl) ?: return block(null)
        val header = cookieHeader(targetUrl) ?: return block(null)
        val lines = netscapeLines(".${registrableDomain(host)}", header)
        if (lines.isEmpty()) return block(null)

        val dir = File(context.filesDir, "cookies").apply { mkdirs() }
        val file = File(dir, "c-${UUID.randomUUID()}.txt")
        return try {
            file.createNewFile()
            // belt-and-braces: filesDir is already app-private, narrow the file itself anyway
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
            file.writeText(lines.joinToString("\n", prefix = "$NETSCAPE_HEADER\n", postfix = "\n"))
            block(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    /** Settings "Clear cookies". */
    fun clearAll() = CookieManager.getInstance().let {
        it.removeAllCookies(null)
        it.flush()
    }

    /**
     * ONE query, on https://<host>: CookieManager.getCookie(host) already returns domain-wide
     * cookies that apply to that host, so querying the registrable domain too would only widen
     * the export to sibling subdomains for no gain.
     */
    private fun cookieHeader(url: String): String? {
        val host = hostOf(url) ?: return null
        return CookieManager.getInstance().getCookie("https://$host")?.takeIf { it.isNotBlank() }
    }

    private fun hostOf(url: String): String? =
        runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
}

internal const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"

// ponytail: naive eTLD+1 -- no Public Suffix List, just "last two labels" plus the few two-part
// TLDs we actually hit. Upgrade path: bundle a PSL if a real site ever mis-scopes.
private val TWO_PART_TLDS = setOf("co.uk", "com.au", "co.jp", "com.br")

internal fun registrableDomain(host: String): String {
    val labels = host.split('.')
    if (labels.size <= 2) return host
    val lastTwo = labels.takeLast(2).joinToString(".")
    return if (lastTwo in TWO_PART_TLDS) labels.takeLast(3).joinToString(".") else lastTwo
}

/**
 * "n=v; n2=v2" (all CookieManager gives us) -> Netscape lines, 7 tab-separated fields:
 * domain, includeSubdomains, path, secure, expiry, name, value.
 * Expiry 0 = session cookie; yt-dlp loads with ignore_expires=True so it is kept.
 */
internal fun netscapeLines(domain: String, cookieHeader: String): List<String> =
    cookieHeader.split(';').mapNotNull { pair ->
        val t = pair.trim()
        val eq = t.indexOf('=') // FIRST '=' only -- values legitimately contain '='
        if (eq <= 0) return@mapNotNull null
        "$domain\tTRUE\t/\tTRUE\t0\t${t.substring(0, eq)}\t${t.substring(eq + 1)}"
    }
