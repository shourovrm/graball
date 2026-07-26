package com.graball.browser

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.graball.download.Enqueue
import com.graball.resolve.MediaKind
import com.graball.resolve.ResolveResult
import com.graball.resolve.ResolvedItem
import com.graball.resolve.Resolver
import com.graball.resolve.Variant
import com.graball.ui.picker.PickerScreen
import kotlinx.coroutines.launch

private sealed interface BrowserSheetState {
    object Resolving : BrowserSheetState
    data class Found(val items: List<ResolvedItem>) : BrowserSheetState
    data class Empty(val message: String) : BrowserSheetState
}

/**
 * Sniffed hit -> direct-download ResolvedItem. MSE markers aren't real fetchable URLs (they
 * signal "run yt-dlp on the page instead"), so they never become a direct download candidate.
 */
private fun SniffHit.toResolvedItemOrNull(): ResolvedItem? {
    if (source == Source.MEDIA_HOOK) return null
    val clean = url.substringBefore('?').substringBefore('#')
    val title = clean.substringAfterLast('/').ifBlank { "media" }
    val e = ext ?: "bin"
    return ResolvedItem(
        sourceUrl = url,
        title = title,
        thumbnail = null,
        durationSec = null,
        kind = kind,
        variants = listOf(
            Variant(
                formatId = "direct",
                label = e,
                ext = e,
                sizeBytes = null,
                hasVideo = kind == MediaKind.VIDEO,
                hasAudio = kind == MediaKind.AUDIO || kind == MediaKind.VIDEO,
                height = null,
                needsMux = false,
            ),
        ),
    )
}

/** In-app WebView browser with a network/DOM/MSE sniffer feeding a picker sheet off its FAB. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(startUrl: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SniffStore() }

    var urlText by remember { mutableStateOf(startUrl ?: "") }
    var pageTitle by remember { mutableStateOf<String?>(null) }
    var backEnabled by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(100) }
    var showSheet by remember { mutableStateOf(false) }
    var sheetState by remember { mutableStateOf<BrowserSheetState>(BrowserSheetState.Resolving) }

    val webView = remember {
        WebView(context).apply {
            installSniffer(store) { view, title, url ->
                pageTitle = title
                url?.let { urlText = it }
                backEnabled = view.canGoBack()
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }
            }
            startUrl?.takeIf { it.isNotBlank() }?.let { loadUrl(it) }
        }
    }

    BackHandler(enabled = backEnabled) { webView.goBack() }

    // Fires yt-dlp on the current page URL; result handed back on the composition's scope.
    fun resolveCurrentPage(onResult: (ResolveResult) -> Unit) {
        val url = webView.url ?: urlText
        scope.launch { onResult(Resolver().resolve(url)) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    val target = urlText.trim().let { if (it.startsWith("http")) it else "https://$it" }
                    webView.loadUrl(target)
                }),
            )
        }
        pageTitle?.let {
            Text(
                it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

            BadgedBox(
                badge = { if (store.hits.size > 0) Badge { Text("${store.hits.size}") } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(56.dp)
                        .combinedClickable(
                            onClick = {
                                showSheet = true
                                sheetState = BrowserSheetState.Resolving
                                resolveCurrentPage { result ->
                                    val sniffed = store.hits.mapNotNull { it.toResolvedItemOrNull() }
                                    sheetState = when (result) {
                                        is ResolveResult.Success -> {
                                            // ponytail: Variant carries no source URL, so dedup is
                                            // by ResolvedItem.sourceUrl only (best available match).
                                            val existing = result.items.map { it.sourceUrl }.toSet()
                                            BrowserSheetState.Found(
                                                result.items + sniffed.filter { it.sourceUrl !in existing },
                                            )
                                        }
                                        is ResolveResult.Failure -> if (sniffed.isEmpty()) {
                                            BrowserSheetState.Empty("Nothing found on this page")
                                        } else {
                                            BrowserSheetState.Found(sniffed)
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                resolveCurrentPage { result ->
                                    val best = when (result) {
                                        is ResolveResult.Success ->
                                            result.items.firstOrNull()?.let { item -> item.bestVariant()?.let { item to it } }
                                        is ResolveResult.Failure -> {
                                            val sniffed = store.hits.mapNotNull { it.toResolvedItemOrNull() }
                                            // ponytail: no size data on sniffed hits, so "largest" only
                                            // means "prefer a VIDEO-kind hit"; upgrade if sizes ever known.
                                            val pick = sniffed.firstOrNull { it.kind == MediaKind.VIDEO } ?: sniffed.firstOrNull()
                                            pick?.let { item -> item.bestVariant()?.let { item to it } }
                                        }
                                    }
                                    if (best != null) {
                                        Enqueue.enqueue(context, listOf(best))
                                        Toast.makeText(context, "Queued", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Nothing found on this page", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        ),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Sniffed media",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            when (val s = sheetState) {
                is BrowserSheetState.Resolving -> Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is BrowserSheetState.Found -> PickerScreen(
                    items = s.items,
                    onDownload = { sel -> Enqueue.enqueue(context, sel); showSheet = false },
                    onCancel = { showSheet = false },
                )

                is BrowserSheetState.Empty -> Column(Modifier.padding(24.dp)) {
                    Text(s.message)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showSheet = false }) { Text("Close") }
                }
            }
        }
    }
}
