package com.graball.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.graball.GraballApp
import com.graball.cookies.SignInActivity
import com.graball.download.Enqueue
import com.graball.resolve.ResolveResult
import com.graball.resolve.Resolver
import com.graball.resolve.UrlExtractor
import com.graball.ui.theme.GraballTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Share-sheet entry: floating sheet over the host app. Gets no URL of its own logged, ever. */
class ShareActivity : ComponentActivity() {

    private var uiState by mutableStateOf<ShareUiState>(ShareUiState.NoUrl)
    private var lastUrl: String? = null
    private var resolvedWithCookies = false

    // returns from the in-app WebView login -> retry the same URL, this time with cookies
    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) lastUrl?.let { startResolve(it, withCookies = true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractUrl()?.let { startResolve(it) }

        setContent {
            GraballTheme {
                ShareSheet(
                    state = uiState,
                    onDismiss = ::finish,
                    onGo = { url -> startResolve(url) },
                    onRetry = { (uiState as? ShareUiState.Error)?.url?.let { startResolve(it) } },
                    onSignIn = { lastUrl?.let { signIn.launch(SignInActivity.intent(this, it)) } },
                    onCopyLog = ::copyLog,
                    onDownload = { selection ->
                        // only carry a domain when the resolve actually needed cookies
                        val domain = lastUrl?.takeIf { resolvedWithCookies }?.let(::hostOf)
                        Enqueue.enqueue(this, selection, cookieDomain = domain)
                        Toast.makeText(this, "Queued ${selection.size}", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                )
            }
        }
    }

    private fun extractUrl(): String? {
        val fromIntent = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(UrlExtractor::extract)
            Intent.ACTION_VIEW -> intent.data?.toString()
            else -> null
        }
        if (fromIntent != null) return fromIntent
        // no share text (e.g. launched via clipboard shortcut): fall back to clipboard
        val clipText = getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        return clipText?.let(UrlExtractor::extract)
    }

    private fun startResolve(url: String, withCookies: Boolean = false) {
        val app = application as GraballApp
        lastUrl = url
        uiState = ShareUiState.Resolving(url, startingEngine = !app.engineReady)
        lifecycleScope.launch {
            var waited = 0L
            while (!app.engineReady && waited < 15_000L) {
                delay(500L)
                waited += 500L
            }
            uiState = ShareUiState.Resolving(url, startingEngine = false)
            when (val result = Resolver(this@ShareActivity).resolve(url, withCookies)) {
                is ResolveResult.Success -> {
                    resolvedWithCookies = withCookies
                    uiState = ShareUiState.Picked(result.items)
                }
                is ResolveResult.Failure -> uiState = ShareUiState.Error(result.error, result.rawLog, url)
            }
        }
    }

    // display-only: copies raw log to clipboard on explicit user tap, never to logcat
    private fun copyLog(rawLog: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("graball-log", rawLog))
    }
}
