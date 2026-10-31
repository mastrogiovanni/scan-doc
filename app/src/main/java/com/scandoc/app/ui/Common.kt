package com.scandoc.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.scandoc.app.pdf.PageFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val WhatsAppGreen = Color(0xFF25D366)

/** Carica una miniatura JPEG in background. */
@Composable
fun rememberThumbnail(path: String?, version: Long): ImageBitmap? {
    val thumb by produceState<ImageBitmap?>(null, path, version) {
        value = path?.let { withContext(Dispatchers.IO) { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    }
    return thumb
}

fun pagesLabel(n: Int) = if (n == 1) "1 pagina" else "$n pagine"

@Composable
fun RenameDialog(currentName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(currentName, TextRange(0, currentName.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nome documento") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Il PDF verrà chiamato \"${value.text.trim()}.pdf\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = valid) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun ConfirmDeleteDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfDialog(
    docName: String,
    onDismiss: () -> Unit,
    onShare: (PageFormat, Boolean) -> Unit,
) {
    var format by remember { mutableStateOf(PageFormat.A4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Esporta PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("$docName.pdf", fontWeight = FontWeight.Bold)
                Text("Formato pagina", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PageFormat.entries.forEachIndexed { i, f ->
                        SegmentedButton(
                            selected = format == f,
                            onClick = { format = f },
                            shape = SegmentedButtonDefaults.itemShape(i, PageFormat.entries.size)
                        ) { Text(f.label, maxLines = 1) }
                    }
                }
                Button(
                    onClick = { onShare(format, true) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen)
                ) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Invia con WhatsApp", fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(
                    onClick = { onShare(format, false) },
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("Altre app / Salva…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}
