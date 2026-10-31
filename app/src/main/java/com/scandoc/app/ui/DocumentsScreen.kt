package com.scandoc.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scandoc.app.ScanDocument
import com.scandoc.app.ScanViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Schermata principale: elenco dei documenti salvati. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(vm: ScanViewModel) {
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        vm.importNew(uris)
    }
    var renaming by remember { mutableStateOf<ScanDocument?>(null) }
    var deleting by remember { mutableStateOf<ScanDocument?>(null) }
    var exporting by remember { mutableStateOf<ScanDocument?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ScanDoc", fontWeight = FontWeight.Bold)
                        if (vm.documents.isNotEmpty()) Text(
                            if (vm.documents.size == 1) "1 documento" else "${vm.documents.size} documenti",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                )
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = vm::startNewScan, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Default.CameraAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scansiona")
                    }
                    FilledTonalButton(
                        onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Importa")
                    }
                }
            }
        }
    ) { padding ->
        if (vm.documents.isEmpty()) {
            EmptyDocuments(Modifier.padding(padding))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(padding)
            ) {
                items(vm.documents, key = { it.id }) { doc ->
                    DocumentRow(
                        doc = doc,
                        onOpen = { vm.openDocument(doc.id) },
                        onExport = { exporting = doc },
                        onRename = { renaming = doc },
                        onDelete = { deleting = doc },
                    )
                }
            }
        }
    }

    renaming?.let { doc ->
        RenameDialog(doc.name, onDismiss = { renaming = null }) {
            vm.renameDocument(doc.id, it); renaming = null
        }
    }
    deleting?.let { doc ->
        ConfirmDeleteDialog(
            "Eliminare il documento?",
            "\"${doc.name}\" e le sue ${pagesLabel(doc.pages.size)} verranno eliminati definitivamente.",
            onDismiss = { deleting = null },
            onConfirm = { vm.deleteDocument(doc.id); deleting = null }
        )
    }
    exporting?.let { doc ->
        PdfDialog(doc.name, onDismiss = { exporting = null }) { format, whatsapp ->
            exporting = null
            vm.createAndSharePdf(doc.id, format, whatsapp)
        }
    }
}

private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ITALY)

@Composable
private fun DocumentRow(
    doc: ScanDocument,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val first = doc.pages.firstOrNull()
    val thumb = rememberThumbnail(first?.thumbPath, first?.version ?: 0)
    var menu by remember { mutableStateOf(false) }

    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 60.dp, height = 80.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE3E7E5)),
                contentAlignment = Alignment.Center
            ) {
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Default.Description, null, tint = Color.Gray)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    doc.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${pagesLabel(doc.pages.size)} · ${dateFormat.format(Date(doc.updatedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            IconButton(onClick = onExport, enabled = doc.pages.isNotEmpty()) {
                Icon(Icons.Default.PictureAsPdf, "Esporta PDF", tint = if (doc.pages.isNotEmpty()) WhatsAppGreen else Color.LightGray)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Altro") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rinomina") },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                        onClick = { menu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Elimina", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDocuments(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(120.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.DocumentScanner, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(24.dp))
        Text("Nessun documento", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tocca \"Scansiona\" per fotografare una o più pagine: verranno salvate in un nuovo documento che potrai esportare in PDF.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}
