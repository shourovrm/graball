package com.graball.cookies

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.graball.ui.theme.GraballTheme

/**
 * Plain in-app sign-in WebView: the user logs in, cookies land in this app's own CookieManager.
 * No sniffer hooks, no JS bridge, no logging of the URL. Nothing here reads cookie values.
 */
class SignInActivity : ComponentActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true // login pages are JS-only in practice
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = WebViewClient() // keep every navigation inside this activity
            CookieManager.getInstance().setAcceptCookie(true)
            // OAuth/SSO hops are third-party by definition; export stays domain-scoped regardless
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl(url)
        }

        setContent {
            GraballTheme {
                // ponytail: no canGoBack state to keep in sync -- ask the WebView at press time
                BackHandler { if (web.canGoBack()) web.goBack() else finish() }
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(host, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = ::done) { Text("Done") }
                    }
                    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    private fun done() {
        CookieManager.getInstance().flush() // persist before the caller re-resolves
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, SignInActivity::class.java).putExtra(EXTRA_URL, url)
    }
}
