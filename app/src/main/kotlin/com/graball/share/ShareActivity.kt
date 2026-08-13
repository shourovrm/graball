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
import com.graball.MainActivity
import com.graball.cookies.SignInActivity
import com.graball.download.Enqueue
import com.graball.resolve.ResolveResult
import com.graball.resolve.Resolver
import com.graball.resolve.UrlExtractor
import com.graball.ui.theme.GraballThemeFromPrefs
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
        addOnNewIntentListener { fresh ->
            // singleTask: a re-share routes here instead of a new instance
            intent = fresh
            resolvedWithCookies = false
            extractUrl()?.let { startResolve(it) } ?: run { uiState = ShareUiState.NoUrl }
        }

        setContent {
            GraballThemeFromPrefs {
                ShareSheet(
                    state = uiState,
                    onDismiss = ::finish,
                    onGo = { url -> startResolve(url) },
                    onPaste = ::readClipboardUrl,
                    onRetry = { (uiState as? ShareUiState.Error)?.url?.let { startResolve(it) } },
                    onSignIn = { lastUrl?.let { signIn.launch(SignInActivity.intent(this, it)) } },
                    onCopyLog = ::copyLog,
                    onDownload = { selection ->
                        // only carry a domain when the resolve actually needed cookies
                        val domain = lastUrl?.takeIf { resolvedWithCookies }?.let(::hostOf)
                        Toast.makeText(this, "Queued ${selection.size}", Toast.LENGTH_SHORT).show()
                        // finish() only after the FGS start: API 31+ bans background FGS starts
                        Enqueue.enqueue(this, selection, cookieDomain = domain, onDone = ::openDownloads)
                    },
                )
            }
        }
    }

    private fun extractUrl(): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(UrlExtractor::extract)
        // explicit intents bypass the manifest <data> filter: allowlist scheme here, at the boundary
        Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "http" || it.scheme == "https" }?.toString()
        else -> null
    }

    /** Clipboard is only read behind an explicit user tap — never automatically on launch. */
    fun readClipboardUrl(): String? =
        getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.let(UrlExtractor::extract)

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

    /** Hands off to the Downloads tab instead of dropping the user back into the host app --
     *  a queued download the user can't see reads as nothing having happened. */
    private fun openDownloads() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    // display-only: copies raw log to clipboard on explicit user tap, never to logcat
    private fun copyLog(rawLog: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("graball-log", rawLog))
    }
}
