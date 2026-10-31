package com.scandoc.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
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
import com.scandoc.app.Page
import com.scandoc.app.ScanViewModel

/** Documento aperto: pagine da aggiungere, togliere, riordinare; rinomina, elimina, esporta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(vm: ScanViewModel) {
    val doc = vm.currentDoc
    if (doc == null) {
        LaunchedEffect(Unit) { vm.closeDocument() }
        return
    }
    BackHandler { vm.closeDocument() }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        vm.importFromGallery(uris)
    }
    var renaming by remember { mutableStateOf(false) }
    var deletingDoc by remember { mutableStateOf(false) }
    var deletingPage by remember { mutableStateOf<Page?>(null) }
    var exporting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::closeDocument) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
                title = {
                    Column(Modifier.clickable { renaming = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                doc.name, fontWeight = FontWeight.Bold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Edit, "Rinomina", Modifier.size(18.dp))
                        }
                        Text(pagesLabel(doc.pages.size), style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = { deletingDoc = true }) { Icon(Icons.Default.DeleteForever, "Elimina documento") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::addPagesWithCamera, modifier = Modifier.weight(1f).height(52.dp)) {
                            Icon(Icons.Default.AddAPhoto, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Aggiungi")
                        }
                        FilledTonalButton(
                            onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Galleria")
                        }
                    }
                    Button(
                        onClick = { exporting = true },
                        enabled = doc.pages.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = WhatsAppGreen)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Esporta PDF e condividi", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        if (doc.pages.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.CameraAlt, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Il documento non ha pagine.\nAggiungine una con la fotocamera o dalla galleria.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding)
            ) {
                itemsIndexed(doc.pages, key = { _, p -> p.id }) { i, p ->
                    PageCard(
                        page = p, index = i, count = doc.pages.size,
                        onEdit = { vm.editPage(p) },
                        onDelete = { deletingPage = p },
                        onMove = { vm.movePage(p, it) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    if (renaming) {
        RenameDialog(doc.name, onDismiss = { renaming = false }) {
            vm.renameDocument(doc.id, it); renaming = false
        }
    }
    if (deletingDoc) {
        ConfirmDeleteDialog(
            "Eliminare il documento?",
            "\"${doc.name}\" e le sue ${pagesLabel(doc.pages.size)} verranno eliminati definitivamente.",
            onDismiss = { deletingDoc = false },
            onConfirm = { deletingDoc = false; vm.deleteDocument(doc.id) }
        )
    }
    deletingPage?.let { p ->
        ConfirmDeleteDialog(
            "Eliminare la pagina?",
            "La pagina ${doc.pages.indexOf(p) + 1} verrà rimossa dal documento.",
            onDismiss = { deletingPage = null },
            onConfirm = { vm.deletePage(p); deletingPage = null }
        )
    }
    if (exporting) {
        PdfDialog(doc.name, onDismiss = { exporting = false }) { format, whatsapp ->
            exporting = false
            vm.createAndSharePdf(doc.id, format, whatsapp)
        }
    }
}

@Composable
private fun PageCard(
    page: Page, index: Int, count: Int,
    onEdit: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumb = rememberThumbnail(page.thumbPath, page.version)
    Card(shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .background(Color(0xFFE9ECEB))
                .clickable(onClick = onEdit),
            contentAlignment = Alignment.Center
        ) {
            thumb?.let { Image(it, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit) }
            Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(26.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) { Text("${index + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Sposta prima")
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Modifica") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Elimina pagina") }
            IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Sposta dopo")
            }
        }
    }
}
