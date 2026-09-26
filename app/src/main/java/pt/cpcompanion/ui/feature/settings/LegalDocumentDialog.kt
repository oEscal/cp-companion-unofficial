package pt.cpcompanion.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.cpcompanion.R

/** Reads bundled documents offline. Asset names are selected by the app, never by external input. */
@Composable
internal fun LegalDocumentDialog(title: String, asset: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("", asset) {
        value = withContext(Dispatchers.IO) {
            context.assets.open(asset).bufferedReader().use { it.readText() }
        }
    }
    val paragraphs = remember(text) { text.split("\n\n").filter(String::isNotBlank) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(paragraphs) { paragraph ->
                    SelectionContainer { Text(paragraph + "\n") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_document)) } },
    )
}
