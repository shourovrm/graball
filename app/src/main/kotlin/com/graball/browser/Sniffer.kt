package com.graball.browser

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URL

// All injected JS below is our own static source — never built from page/user content, never eval'd.
private const val RESET_JS = "window.__graballHooked = false;"

private const val DOM_SCRAPE_JS = """
(function(){
  try{
    document.querySelectorAll('a[href]').forEach(function(a){ try{ window.GraballSniff.report(a.href,'dom'); }catch(e){} });
    document.querySelectorAll('video[src],source[src],audio[src]').forEach(function(el){ try{ window.GraballSniff.report(el.src,'dom'); }catch(e){} });
    document.querySelectorAll('img[src]').forEach(function(img){ try{ if(img.naturalWidth>=200){ window.GraballSniff.report(img.src,'dom'); } }catch(e){} });
  }catch(e){}
})();
"""

private const val HOOK_JS = """
(function(){
  if (window.__graballHooked) return;
  window.__graballHooked = true;
  try{
    var of = window.fetch;
    if (of) window.fetch = function(input, init){
      try{ var u = typeof input==='string' ? input : (input && input.url); if(u) window.GraballSniff.report(new URL(u, location.href).href, 'network'); }catch(e){}
      return of.apply(this, arguments);
    };
  }catch(e){}
  try{
    var oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url){
      try{ if(url) window.GraballSniff.report(new URL(url, location.href).href, 'network'); }catch(e){}
      return oo.apply(this, arguments);
    };
  }catch(e){}
  try{
    if (window.MediaSource && MediaSource.prototype.addSourceBuffer){
      var oa = MediaSource.prototype.addSourceBuffer;
      MediaSource.prototype.addSourceBuffer = function(mime){
        try{ window.GraballSniff.report(location.href + ' [MSE stream]', 'mse'); }catch(e){}
        return oa.apply(this, arguments);
      };
    }
  }catch(e){}
})();
"""

/**
 * JS bridge, exposed to the page as `GraballSniff`. SECURITY: only accepts two plain strings,
 * validates the URL with java.net.URL before storing anything, never eval()s page content, and
 * exposes nothing back to the page beyond a void method — the page can push data in, nothing out.
 */
class SniffBridge(private val store: SniffStore) {
    private val main = Handler(Looper.getMainLooper()) // JS interface calls land off the UI thread

    @JavascriptInterface
    fun report(url: String?, source: String?) {
        if (url.isNullOrBlank()) return
        val src = when (source) {
            "dom" -> Source.DOM
            "mse" -> Source.MEDIA_HOOK
            else -> Source.NETWORK
        }
        // MSE marker is "<pageUrl> [MSE stream]" — validate the URL part, not the whole string.
        val base = if (src == Source.MEDIA_HOOK) url.removeSuffix(" [MSE stream]") else url
        val proto = runCatching { URL(base).protocol }.getOrNull()
        if (proto != "http" && proto != "https") return
        main.post { store.add(url, src) }
    }
}

// ponytail: one shared empty response for every blocked request — safe to reuse since a
// zero-length ByteArrayInputStream always EOFs immediately, no per-call state to exhaust.
private val EMPTY_RESPONSE = WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

/** WebViewClient that mirrors every request into the SniffStore. Never blocks network thread on I/O. */
private class SniffingClient(
    private val store: SniffStore,
    private val adblockEnabled: () -> Boolean,
    private val httpsOnly: () -> Boolean,
    private val onPage: (view: WebView, title: String?, url: String?) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (adblockEnabled() && AdBlocker.isBlocked(request.url.host)) return EMPTY_RESPONSE
        val accept = request.requestHeaders["Accept"] // cheap header peek, no extra round trip
        // called off the UI thread; hop back before touching Compose state
        view.post { store.add(url, Source.NETWORK, mimeHint = accept?.substringBefore(',')) }
        return null
    }

    private var lastUpgraded: String? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (httpsOnly() && url.scheme == "http") {
            val target = "https://" + url.toString().removePrefix("http://")
            // http-only site redirecting back down would loop forever: one upgrade attempt per URL
            if (target == lastUpgraded) return false
            lastUpgraded = target
            view.loadUrl(target)
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        store.clearForPage(url ?: "")
        view.evaluateJavascript(RESET_JS, null)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        view.evaluateJavascript(DOM_SCRAPE_JS + HOOK_JS, null)
        onPage(view, view.title, url)
    }
}

/** Locks down settings and wires the sniffer client + bridge. Call once per WebView instance. */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.installSniffer(
    store: SniffStore,
    adblockEnabled: () -> Boolean = { false },
    httpsOnly: () -> Boolean = { false },
    onPage: (view: WebView, title: String?, url: String?) -> Unit = { _, _, _ -> },
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    webViewClient = SniffingClient(store, adblockEnabled, httpsOnly, onPage)
    addJavascriptInterface(SniffBridge(store), "GraballSniff")
}
