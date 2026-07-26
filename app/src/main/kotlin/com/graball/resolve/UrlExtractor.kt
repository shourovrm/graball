package com.graball.resolve

import android.util.Patterns

/** Pulls the first http(s) URL out of shared text (ACTION_SEND payloads mix URL + caption). */
object UrlExtractor {
    private val TRAILING_PUNCT = charArrayOf(')', '>', ',', '.', ';', '!', '?', '"', '\'')

    fun extract(text: String): String? {
        val matcher = Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val candidate = matcher.group()
            if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                return candidate.trimEnd(*TRAILING_PUNCT)
            }
        }
        return null
    }
}
