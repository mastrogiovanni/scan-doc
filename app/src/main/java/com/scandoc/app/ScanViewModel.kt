package com.scandoc.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scandoc.app.pdf.PageFormat
import com.scandoc.app.pdf.PdfBuilder
import com.scandoc.app.processing.DocumentDetector
import com.scandoc.app.processing.FilterType
import com.scandoc.app.processing.Filters
import com.scandoc.app.processing.ImageUtils
import com.scandoc.app.processing.PerspectiveCorrector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Page(
    val id: String,
    val originalPath: String,
    val corners: List<PointF>,
    val filter: FilterType,
    val rotation: Int,
    val outputPath: String,
    val thumbPath: String,
    val version: Long = System.currentTimeMillis(),
)

data class ScanDocument(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<Page>,
)

enum class Screen { DOCUMENTS, DOCUMENT, CAMERA, CROP, FILTER }

/** Stato della pagina in lavorazione (ritaglio + filtro). */
class EditSession(
    val originalFile: File,
    val original: Bitmap,
    val detected: List<PointF>?,
    initialCorners: List<PointF>,
    val replacePageId: String?,
    initialFilter: FilterType,
    initialRotation: Int,
) {
    var corners by mutableStateOf(initialCorners)
    var filter by mutableStateOf(initialFilter)
    var rotation by mutableStateOf(initialRotation)
    var warped: Bitmap? = null
    var preview by mutableStateOf<Bitmap?>(null)          // raddrizzata, ridotta
    var filteredPreview by mutableStateOf<Bitmap?>(null)  // con filtro applicato
    val thumbnails = mutableStateMapOf<FilterType, Bitmap>()
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val pagesDir = File(app.filesDir, "pages").apply { mkdirs() }
    private val indexFile = File(app.filesDir, "documents.json")
    private val legacyIndexFile = File(app.filesDir, "pages.json")

    /** Documenti salvati, dal più recente. */
    val documents = mutableStateListOf<ScanDocument>()

    /** Documento aperto (null = nuova scansione non ancora salvata). */
    var currentDocId by mutableStateOf<String?>(null)
        private set
    val currentDoc: ScanDocument? get() = documents.firstOrNull { it.id == currentDocId }

    var screen by mutableStateOf(Screen.DOCUMENTS)
    var busy by mutableStateOf<String?>(null)
    var edit by mutableStateOf<EditSession?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    /** Condivisione da eseguire dall'Activity (serve un context di Activity). */
    var pendingShare by mutableStateOf<Pair<File, Boolean>?>(null)

    private var defaultFilter = FilterType.MAGIC
    private val importQueue = ArrayDeque<Uri>()
    private var filterJob: Job? = null
    private var thumbsJob: Job? = null

    init {
        load()
    }

    // ------------------------------------------------------------------ navigazione

    /** Nuova scansione dalla schermata principale: crea un nuovo documento al primo salvataggio. */
    fun startNewScan() {
        currentDocId = null
        screen = Screen.CAMERA
    }

    fun importNew(uris: List<Uri>) {
        if (uris.isEmpty()) return
        currentDocId = null
        importFromGallery(uris)
    }

    fun openDocument(id: String) {
        currentDocId = id
        screen = Screen.DOCUMENT
    }

    fun addPagesWithCamera() {
        screen = Screen.CAMERA
    }

    /** Torna al documento aperto, oppure alla lista se si stava creando un documento nuovo. */
    fun backToContext() {
        screen = if (currentDoc != null) Screen.DOCUMENT else Screen.DOCUMENTS
    }

    fun closeDocument() {
        currentDocId = null
        screen = Screen.DOCUMENTS
    }

    // ------------------------------------------------------------------ acquisizione

    fun newPhotoFile(): File = File(pagesDir, "orig_${UUID.randomUUID()}.jpg")

    fun onPhotoCaptured(file: File) = startEdit(file, null)

    /** Importa immagini nel documento corrente (o in uno nuovo se nessuno è aperto). */
    fun importFromGallery(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importQueue.clear()
        importQueue.addAll(uris)
        processNextImport()
    }

    private fun processNextImport() {
        val uri = importQueue.removeFirstOrNull() ?: return
        viewModelScope.launch {
            busy = "Caricamento…"
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val f = newPhotoFile()
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                        f.outputStream().use { input.copyTo(it) }
                    }
                    f
                }.getOrNull()
            }
            busy = null
            if (file == null) {
                message = "Impossibile leggere l'immagine"
                if (importQueue.isNotEmpty()) processNextImport() else backToContext()
            } else {
                startEdit(file, null)
            }
        }
    }

    private fun startEdit(file: File, page: Page?) {
        viewModelScope.launch {
            busy = "Rilevamento bordi…"
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val bmp = ImageUtils.loadBitmap(file)
                    val detected = if (page == null) runCatching { DocumentDetector.detect(bmp) }.getOrNull() else null
                    Pair(bmp, detected)
                }
            }
            busy = null
            result.onSuccess { (bmp, detected) ->
                edit = EditSession(
                    originalFile = file,
                    original = bmp,
                    detected = detected,
                    initialCorners = page?.corners ?: detected ?: DocumentDetector.defaultCorners(bmp.width, bmp.height),
                    replacePageId = page?.id,
                    initialFilter = page?.filter ?: defaultFilter,
                    initialRotation = page?.rotation ?: 0,
                )
                if (page == null && detected == null) message = "Bordi non trovati: posiziona gli angoli manualmente"
                screen = Screen.CROP
            }.onFailure {
                message = "Errore nel caricamento dell'immagine"
                backToContext()
            }
        }
    }

    fun editPage(page: Page) = startEdit(File(page.originalPath), page)

    // ------------------------------------------------------------------ ritaglio

    fun setCorners(c: List<PointF>) {
        edit?.corners = c
    }

    fun useDetectedCorners() {
        val e = edit ?: return
        if (e.detected != null) {
            e.corners = e.detected
            return
        }
        viewModelScope.launch {
            busy = "Rilevamento bordi…"
            val d = withContext(Dispatchers.Default) { runCatching { DocumentDetector.detect(e.original) }.getOrNull() }
            busy = null
            if (d != null) e.corners = d else message = "Nessun documento rilevato"
        }
    }

    fun useFullImage() {
        val e = edit ?: return
        e.corners = DocumentDetector.fullCorners(e.original.width, e.original.height)
    }

    fun confirmCrop() {
        val e = edit ?: return
        viewModelScope.launch {
            busy = "Raddrizzamento…"
            val corners = e.corners
            val rotation = e.rotation
            val filter = e.filter
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val rotated = ImageUtils.rotate(PerspectiveCorrector.warp(e.original, corners), rotation)
                    val prev = ImageUtils.scaleDown(rotated, 1400)
                    Triple(rotated, prev, Filters.apply(prev, filter))
                }
            }
            busy = null
            val (warped, preview, filtered) = result.getOrElse {
                message = "Errore durante il raddrizzamento"
                return@launch
            }
            e.warped = warped
            e.preview = preview
            e.filteredPreview = filtered
            screen = Screen.FILTER
            buildThumbnails(e)
        }
    }

    // ------------------------------------------------------------------ filtri

    private fun buildThumbnails(e: EditSession) {
        thumbsJob?.cancel()
        e.thumbnails.clear()
        val src = e.preview ?: return
        thumbsJob = viewModelScope.launch(Dispatchers.Default) {
            val small = ImageUtils.scaleDown(src, 220)
            for (f in FilterType.entries) {
                ensureActive()
                val t = Filters.apply(small, f)
                withContext(Dispatchers.Main) { e.thumbnails[f] = t }
            }
        }
    }

    fun selectFilter(f: FilterType) {
        val e = edit ?: return
        e.filter = f
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val src = e.preview ?: return@launch
            val out = withContext(Dispatchers.Default) { Filters.apply(src, f) }
            if (e.filter == f) e.filteredPreview = out
        }
    }

    fun rotateResult() {
        val e = edit ?: return
        val src = e.warped ?: return
        viewModelScope.launch {
            busy = "Rotazione…"
            val filter = e.filter
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val r = ImageUtils.rotate(src, 90)
                    val prev = ImageUtils.scaleDown(r, 1400)
                    Triple(r, prev, Filters.apply(prev, filter))
                }
            }
            busy = null
            val (warped, preview, filtered) = result.getOrElse {
                message = "Errore durante la rotazione"
                return@launch
            }
            e.rotation = (e.rotation + 90) % 360
            e.warped = warped
            e.preview = preview
            e.filteredPreview = filtered
            buildThumbnails(e)
        }
    }

    fun backToCrop() {
        screen = Screen.CROP
    }

    fun savePage(thenCamera: Boolean) {
        val e = edit ?: return
        val warped = e.warped ?: return
        viewModelScope.launch {
            busy = "Salvataggio…"
            val filter = e.filter
            val corners = e.corners
            val rotation = e.rotation
            val page = withContext(Dispatchers.Default) {
                runCatching {
                    val full = Filters.apply(warped, filter)
                    val id = e.replacePageId ?: UUID.randomUUID().toString()
                    val out = File(pagesDir, "page_${id}.jpg")
                    val thumb = File(pagesDir, "thumb_${id}.jpg")
                    ImageUtils.saveJpeg(full, out, 88)
                    ImageUtils.saveJpeg(ImageUtils.scaleDown(full, 400), thumb, 80)
                    Page(id, e.originalFile.absolutePath, corners, filter, rotation, out.absolutePath, thumb.absolutePath)
                }.getOrNull()
            }
            busy = null
            if (page == null) {
                message = "Errore durante il salvataggio della pagina"
                return@launch
            }

            // la prima pagina di una nuova scansione crea il documento
            val doc = currentDoc ?: createDocument()
            val pages = doc.pages.toMutableList()
            val idx = pages.indexOfFirst { it.id == page.id }
            if (idx >= 0) pages[idx] = page else pages.add(page)
            updateDocument(doc.copy(pages = pages))
            defaultFilter = filter

            closeEdit(deleteOriginal = false)
            when {
                importQueue.isNotEmpty() -> processNextImport()
                thenCamera -> screen = Screen.CAMERA
                else -> screen = Screen.DOCUMENT
            }
        }
    }

    /** Annulla la pagina in lavorazione. */
    fun discardEdit() {
        val e = edit
        closeEdit(deleteOriginal = e?.replacePageId == null)
        importQueue.clear()
        backToContext()
    }

    private fun closeEdit(deleteOriginal: Boolean) {
        thumbsJob?.cancel(); filterJob?.cancel()
        val e = edit ?: return
        if (deleteOriginal) e.originalFile.delete()
        edit = null
    }

    // ------------------------------------------------------------------ documenti

    private fun defaultDocName(): String =
        "Scansione " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.ITALY).format(Date())

    private fun createDocument(): ScanDocument {
        val now = System.currentTimeMillis()
        val doc = ScanDocument(UUID.randomUUID().toString(), defaultDocName(), now, now, emptyList())
        documents.add(0, doc)
        currentDocId = doc.id
        return doc
    }

    /** Sostituisce il documento, lo porta in cima alla lista e salva l'indice. */
    private fun updateDocument(doc: ScanDocument) {
        val updated = doc.copy(updatedAt = System.currentTimeMillis())
        documents.removeAll { it.id == doc.id }
        documents.add(0, updated)
        persist()
    }

    fun renameDocument(id: String, name: String) {
        val doc = documents.firstOrNull { it.id == id } ?: return
        val clean = name.trim().ifEmpty { return }
        if (clean != doc.name) updateDocument(doc.copy(name = clean))
    }

    fun deleteDocument(id: String) {
        val doc = documents.firstOrNull { it.id == id } ?: return
        doc.pages.forEach(::deletePageFiles)
        documents.remove(doc)
        persist()
        if (currentDocId == id) closeDocument()
    }

    fun deletePage(p: Page) {
        val doc = currentDoc ?: return
        deletePageFiles(p)
        updateDocument(doc.copy(pages = doc.pages.filter { it.id != p.id }))
    }

    fun movePage(p: Page, delta: Int) {
        val doc = currentDoc ?: return
        val pages = doc.pages.toMutableList()
        val i = pages.indexOfFirst { it.id == p.id }
        val j = i + delta
        if (i < 0 || j !in pages.indices) return
        pages.add(j, pages.removeAt(i))
        updateDocument(doc.copy(pages = pages))
    }

    private fun deletePageFiles(p: Page) {
        listOf(p.originalPath, p.outputPath, p.thumbPath).forEach { File(it).delete() }
    }

    fun createAndSharePdf(docId: String, format: PageFormat, whatsapp: Boolean) {
        val doc = documents.firstOrNull { it.id == docId } ?: return
        if (doc.pages.isEmpty()) {
            message = "Il documento non ha pagine"
            return
        }
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            busy = "Creazione PDF…"
            val pdf = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "pdf").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val safe = doc.name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifEmpty { "Documento" }
                    val f = File(dir, "$safe.pdf")
                    PdfBuilder.build(doc.pages.map { File(it.outputPath) }, f, format)
                    f
                }.getOrNull()
            }
            busy = null
            if (pdf == null) message = "Errore nella creazione del PDF"
            else pendingShare = Pair(pdf, whatsapp)
        }
    }

    // ------------------------------------------------------------------ persistenza

    private fun persist() {
        val arr = JSONArray()
        documents.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("created", d.createdAt)
                put("updated", d.updatedAt)
                put("pages", JSONArray().apply { d.pages.forEach { put(pageToJson(it)) } })
            })
        }
        runCatching {
            // scrittura atomica: evita indici corrotti se l'app viene chiusa a metà
            val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(indexFile)
        }
    }

    private fun pageToJson(p: Page) = JSONObject().apply {
        put("id", p.id)
        put("orig", p.originalPath)
        put("out", p.outputPath)
        put("thumb", p.thumbPath)
        put("filter", p.filter.name)
        put("rotation", p.rotation)
        put("corners", JSONArray().apply { p.corners.forEach { c -> put(c.x.toDouble()); put(c.y.toDouble()) } })
    }

    private fun pageFromJson(o: JSONObject): Page {
        val c = o.getJSONArray("corners")
        return Page(
            id = o.getString("id"),
            originalPath = o.getString("orig"),
            corners = List(4) { k -> PointF(c.getDouble(k * 2).toFloat(), c.getDouble(k * 2 + 1).toFloat()) },
            filter = runCatching { FilterType.valueOf(o.getString("filter")) }.getOrDefault(FilterType.ORIGINAL),
            rotation = o.optInt("rotation", 0),
            outputPath = o.getString("out"),
            thumbPath = o.getString("thumb"),
        )
    }

    private fun load() {
        runCatching {
            if (indexFile.exists()) {
                val arr = JSONArray(indexFile.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pa = o.getJSONArray("pages")
                    val pages = (0 until pa.length())
                        .mapNotNull { runCatching { pageFromJson(pa.getJSONObject(it)) }.getOrNull() }
                        .filter { File(it.outputPath).exists() }
                    documents.add(
                        ScanDocument(
                            o.getString("id"), o.getString("name"),
                            o.optLong("created"), o.optLong("updated"), pages
                        )
                    )
                }
                documents.sortByDescending { it.updatedAt }
            }
        }
        migrateLegacy()
    }

    /** Converte le pagine della versione precedente (documento unico) in un documento. */
    private fun migrateLegacy() {
        if (!legacyIndexFile.exists()) return
        runCatching {
            val arr = JSONArray(legacyIndexFile.readText())
            val pages = (0 until arr.length())
                .mapNotNull { runCatching { pageFromJson(arr.getJSONObject(it)) }.getOrNull() }
                .filter { File(it.outputPath).exists() }
            if (pages.isNotEmpty()) {
                val now = System.currentTimeMillis()
                documents.add(0, ScanDocument(UUID.randomUUID().toString(), defaultDocName(), now, now, pages))
                persist()
            }
        }
        legacyIndexFile.delete()
    }
}
