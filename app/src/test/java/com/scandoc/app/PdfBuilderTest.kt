package com.scandoc.app

import com.scandoc.app.pdf.PageFormat
import com.scandoc.app.pdf.PdfBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfBuilderTest {

    private fun resource(name: String, dir: File): File {
        val f = File(dir, name)
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    @Test
    fun buildsValidPdf() {
        val dir = File(System.getProperty("pdfTestDir") ?: "build/pdf-test").apply { mkdirs() }
        val pages = listOf("page1.jpg", "page2.jpg", "page3.jpg").map { resource(it, dir) }
        assertEquals(Pair(1240, 1754), PdfBuilder.jpegSize(pages[0]))
        assertEquals(Pair(1754, 1240), PdfBuilder.jpegSize(pages[1]))
        assertEquals(Pair(800, 2400), PdfBuilder.jpegSize(pages[2]))
        for (fmt in PageFormat.entries) {
            val out = File(dir, "test_${fmt.name}.pdf")
            PdfBuilder.build(pages, out, fmt)
            val text = out.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(text.startsWith("%PDF-1.4"))
            assertTrue(text.trimEnd().endsWith("%%EOF"))
            assertEquals(3, Regex("/Type /Page ").findAll(text).count())
        }
    }
}
