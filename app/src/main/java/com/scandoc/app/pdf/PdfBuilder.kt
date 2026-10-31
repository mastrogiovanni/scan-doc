package com.scandoc.app.pdf

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale

enum class PageFormat(val label: String) { A4("A4"), FIT("Adatta all'immagine") }

/**
 * Scrittore PDF minimale: ogni pagina contiene un'immagine JPEG incorporata così com'è
 * (filtro /DCTDecode), senza ricompressione. Risultato: qualità piena e file molto leggeri,
 * ideali per WhatsApp.
 */
object PdfBuilder {

    private const val A4_W = 595.28
    private const val A4_H = 841.89

    fun build(jpegs: List<File>, out: File, format: PageFormat) {
        out.parentFile?.mkdirs()
        CountingStream(BufferedOutputStream(FileOutputStream(out))).use { s ->
            val offsets = ArrayList<Long>()
            fun beginObj(n: Int) {
                while (offsets.size < n) offsets.add(0)
                offsets[n - 1] = s.count
                s.ascii("$n 0 obj\n")
            }

            s.ascii("%PDF-1.4\n")
            s.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

            val n = jpegs.size
            val pageObj = { i: Int -> 3 + i * 3 }
            beginObj(1)
            s.ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            beginObj(2)
            s.ascii("<< /Type /Pages /Count $n /Kids [${(0 until n).joinToString(" ") { "${pageObj(it)} 0 R" }}] >>\nendobj\n")

            jpegs.forEachIndexed { i, jpg ->
                val (pxW, pxH) = jpegSize(jpg)
                val iw = pxW.toDouble()
                val ih = pxH.toDouble()

                val (pw, ph) = when (format) {
                    PageFormat.A4 -> if (iw > ih) Pair(A4_H, A4_W) else Pair(A4_W, A4_H)
                    PageFormat.FIT -> Pair(A4_W, A4_W * ih / iw) // larghezza A4, altezza proporzionale
                }
                val k = minOf(pw / iw, ph / ih)
                val dw = iw * k
                val dh = ih * k
                val dx = (pw - dw) / 2
                val dy = (ph - dh) / 2

                val p = pageObj(i)
                beginObj(p)
                s.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
                        "/Resources << /XObject << /Im0 ${p + 2} 0 R >> /ProcSet [/PDF /ImageC] >> " +
                        "/Contents ${p + 1} 0 R >>\nendobj\n"
                )
                val content = "q\n${f(dw)} 0 0 ${f(dh)} ${f(dx)} ${f(dy)} cm\n/Im0 Do\nQ\n".toByteArray(Charsets.US_ASCII)
                beginObj(p + 1)
                s.ascii("<< /Length ${content.size} >>\nstream\n")
                s.write(content)
                s.ascii("\nendstream\nendobj\n")

                val len = jpg.length()
                beginObj(p + 2)
                s.ascii(
                    "<< /Type /XObject /Subtype /Image /Width $pxW /Height $pxH " +
                        "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length $len >>\nstream\n"
                )
                jpg.inputStream().use { it.copyTo(s) }
                s.ascii("\nendstream\nendobj\n")
            }

            val xref = s.count
            s.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { s.ascii(String.format(Locale.US, "%010d 00000 n \n", it)) }
            s.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
    }

    /** Legge larghezza/altezza dal marker SOF del JPEG (senza decodificare l'immagine). */
    fun jpegSize(file: File): Pair<Int, Int> {
        java.io.DataInputStream(file.inputStream().buffered()).use { inp ->
            require(inp.readUnsignedShort() == 0xFFD8) { "Non è un JPEG" }
            while (true) {
                var marker = inp.readUnsignedByte()
                require(marker == 0xFF) { "JPEG non valido" }
                while (marker == 0xFF) marker = inp.readUnsignedByte()
                if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) continue
                val len = inp.readUnsignedShort()
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    inp.readUnsignedByte() // precisione
                    val h = inp.readUnsignedShort()
                    val w = inp.readUnsignedShort()
                    return Pair(w, h)
                }
                var toSkip = len - 2
                while (toSkip > 0) {
                    val n = inp.skipBytes(toSkip)
                    require(n > 0) { "JPEG troncato" }
                    toSkip -= n
                }
            }
        }
    }

    private fun f(v: Double) = String.format(Locale.US, "%.2f", v)

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
        fun ascii(str: String) = write(str.toByteArray(Charsets.US_ASCII))
    }

    // -------------------------------------------------------------------------------------
    // Condivisione

    fun share(context: Context, pdf: File, whatsapp: Boolean) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, pdf.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (whatsapp) {
            for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                try {
                    context.startActivity(Intent(base).setPackage(pkg))
                    return
                } catch (_: ActivityNotFoundException) {
                }
            }
            Toast.makeText(context, "WhatsApp non è installato", Toast.LENGTH_SHORT).show()
        }
        context.startActivity(Intent.createChooser(base, "Condividi PDF"))
    }
}
