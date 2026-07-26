package com.graball.share

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.graball.resolve.ResolveError
import com.graball.resolve.ResolvedItem
import com.graball.resolve.Variant
import com.graball.ui.picker.PickerScreen
import kotlinx.coroutines.launch

/** Share-sheet UI states. Resolving/Error carry the url only for retry — never logged. */
sealed interface ShareUiState {
    data object NoUrl : ShareUiState
    data class Resolving(val url: String, val startingEngine: Boolean) : ShareUiState
    data class Error(val error: ResolveError, val rawLog: String, val url: String) : ShareUiState
    data class Picked(val items: List<ResolvedItem>) : ShareUiState
}

private fun errorSentence(error: ResolveError): String = when (error) {
    ResolveError.NEEDS_LOGIN -> "This site needs a sign-in to continue."
    ResolveError.DRM -> "This content is DRM-protected and can't be downloaded."
    ResolveError.GEO_BLOCKED -> "This content isn't available in your region."
    ResolveError.EXTRACTOR_BROKEN -> "The site changed and the extractor needs an update."
    ResolveError.NETWORK -> "Network problem — check your connection."
    ResolveError.UNKNOWN -> "Something went wrong."
}

internal fun hostOf(url: String): String = runCatching { Uri.parse(url).host }.getOrNull() ?: url

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    state: ShareUiState,
    onDismiss: () -> Unit,
    onGo: (String) -> Unit,
    onPaste: () -> String?,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onCopyLog: (String) -> Unit,
    onDownload: (List<Pair<ResolvedItem, Variant>>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        when (state) {
            is ShareUiState.NoUrl -> NoUrlContent(onGo = onGo, onPaste = onPaste, onCancel = dismiss)
            is ShareUiState.Resolving -> ResolvingContent(state, onCancel = dismiss)
            is ShareUiState.Error ->
                ErrorContent(state, onRetry = onRetry, onSignIn = onSignIn, onCopyLog = onCopyLog, onClose = dismiss)
            is ShareUiState.Picked -> PickerScreen(
                items = state.items,
                onDownload = onDownload,
                onCancel = dismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SrcRow(url: String) {
    val host = remember(url) { hostOf(url) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(host.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(host, style = MaterialTheme.typography.titleMedium)
            Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ResolvingContent(state: ShareUiState.Resolving, onCancel: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        SrcRow(state.url)
        Spacer(Modifier.height(20.dp))
        Text(if (state.startingEngine) "Starting engine…" else "Resolving media…")
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ErrorContent(
    state: ShareUiState.Error,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onCopyLog: (String) -> Unit,
    onClose: () -> Unit,
) {
    var detailsOpen by remember { mutableStateOf(false) }
    Column(Modifier.padding(24.dp)) {
        Text(errorSentence(state.error), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        when (state.error) {
            ResolveError.NEEDS_LOGIN ->
                Button(onClick = onSignIn) { Text("Sign in") }
            ResolveError.NETWORK ->
                Button(onClick = onRetry) { Text("Retry") }
            else -> Row {
                TextButton(onClick = { onCopyLog(state.rawLog) }) { Text("Copy log") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onClose) { Text("Close") }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { detailsOpen = !detailsOpen }) { Text(if (detailsOpen) "Hide details" else "Details") }
        if (detailsOpen) {
            Text(
                state.rawLog,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun NoUrlContent(onGo: (String) -> Unit, onPaste: () -> String?, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(24.dp)) {
        Text("No link found", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Paste a link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onPaste()?.let { text = it } }) { Text("Paste") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onGo(text) }, enabled = text.isNotBlank()) { Text("Go") }
        }
    }
}
