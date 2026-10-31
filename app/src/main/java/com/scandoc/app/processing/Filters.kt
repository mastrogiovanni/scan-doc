package com.scandoc.app.processing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class FilterType(val label: String) {
    ORIGINAL("Originale"),
    MAGIC("Auto colore"),
    WHITEN("Sbianca / no ombre"),
    WHITE_BALANCE("Bilanciamento bianco"),
    CONTRAST("Più contrasto"),
    VIVID("Colori vivaci"),
    GRAY("Scala di grigi"),
    BW("Bianco e nero"),
}

/**
 * Filtri di miglioramento/correzione colore per documenti.
 *
 * La base dei filtri "documento" è la normalizzazione dell'illuminazione: lo sfondo (carta)
 * viene stimato con una dilatazione morfologica + mediana a bassa risoluzione (che rimuove il
 * testo), e ogni canale viene diviso per questo sfondo. Il risultato elimina ombre e
 * gradienti di luce e rende la carta uniformemente bianca, preservando i colori dell'inchiostro.
 */
object Filters {

    fun apply(src: Bitmap, type: FilterType): Bitmap {
        if (type == FilterType.ORIGINAL) return src.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        val out: Mat = when (type) {
            FilterType.MAGIC -> magicColor(rgb)
            FilterType.WHITEN -> whiten(rgb)
            FilterType.WHITE_BALANCE -> simplestColorBalance(rgb, 0.01)
            FilterType.CONTRAST -> claheContrast(rgb, 2.5)
            FilterType.VIVID -> vivid(rgb)
            FilterType.GRAY -> grayEnhanced(rgb)
            FilterType.BW -> blackAndWhite(rgb)
            FilterType.ORIGINAL -> rgb.clone()
        }
        rgb.release()

        val outRgba = Mat()
        if (out.channels() == 1) Imgproc.cvtColor(out, outRgba, Imgproc.COLOR_GRAY2RGBA)
        else Imgproc.cvtColor(out, outRgba, Imgproc.COLOR_RGB2RGBA)
        out.release()
        val bmp = Bitmap.createBitmap(outRgba.cols(), outRgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, bmp)
        outRgba.release()
        return bmp
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Stima dello sfondo (carta) per ciascun canale, a piena risoluzione.
     *
     * 1. a bassa risoluzione: dilatazione + mediana eliminano il testo;
     * 2. le zone molto più scure del livello locale della carta (foto, timbri, grandi aree
     *    colorate) vengono marcate come "non carta";
     * 3. in quelle zone lo sfondo viene ricostruito per convoluzione normalizzata dai pixel
     *    di carta circostanti, così le aree colorate non vengono sbiancate.
     */
    private fun estimateBackgrounds(chs: List<Mat>): List<Mat> {
        val w = chs[0].cols(); val h = chs[0].rows()
        val scale = min(1.0, 600.0 / max(w, h))
        val smalls = chs.map { ch ->
            val sm = Mat()
            Imgproc.resize(ch, sm, Size(), scale, scale, Imgproc.INTER_AREA)
            sm
        }
        val sw = smalls[0].cols(); val sh = smalls[0].rows()
        val k = oddAtLeast(min(sw, sh) / 30, 7)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        val med = oddAtLeast(k * 2 + 1, 21).coerceAtMost(255)
        for (sm in smalls) {
            Imgproc.dilate(sm, sm, kernel)
            Imgproc.medianBlur(sm, sm, med)
        }
        kernel.release()

        // luminanza dello sfondo e livello della carta su larga scala
        val lum = Mat()
        if (smalls.size == 3) {
            val merged = Mat()
            Core.merge(smalls, merged)
            Imgproc.cvtColor(merged, lum, Imgproc.COLOR_RGB2GRAY)
            merged.release()
        } else smalls[0].copyTo(lum)
        val bigK = oddAtLeast(min(sw, sh) / 4, 31)
        val bigKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(bigK.toDouble(), bigK.toDouble()))
        val big = Mat()
        Imgproc.dilate(lum, big, bigKernel)
        bigKernel.release()
        Imgproc.GaussianBlur(big, big, Size(0.0, 0.0), bigK / 4.0)

        val lumF = Mat(); lum.convertTo(lumF, CvType.CV_32F)
        val bigF = Mat(); big.convertTo(bigF, CvType.CV_32F, 0.82)
        val valid = Mat()
        Core.compare(lumF, bigF, valid, Core.CMP_GE) // 255 = carta
        listOf(lum, big, lumF, bigF).forEach { it.release() }
        val validF = Mat(); valid.convertTo(validF, CvType.CV_32F, 1.0 / 255.0)

        val sigma = bigK / 2.0
        val den = Mat()
        Imgproc.GaussianBlur(validF, den, Size(0.0, 0.0), sigma)
        val lowDen = Mat()
        Core.compare(den, Scalar(1e-3), lowDen, Core.CMP_LT)
        Core.max(den, Scalar(1e-3), den)

        val result = smalls.map { sm ->
            val cf = Mat(); sm.convertTo(cf, CvType.CV_32F)
            val num = Mat()
            Core.multiply(cf, validF, num)
            Imgproc.GaussianBlur(num, num, Size(0.0, 0.0), sigma)
            val fill = Mat()
            Core.divide(num, den, fill)
            cf.copyTo(fill, valid)
            cf.copyTo(fill, lowDen)
            Imgproc.GaussianBlur(fill, fill, Size(0.0, 0.0), 2.0)
            val u8 = Mat(); fill.convertTo(u8, CvType.CV_8U)
            Core.max(u8, Scalar(1.0), u8)
            val bg = Mat()
            Imgproc.resize(u8, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            listOf(cf, num, fill, u8, sm).forEach { it.release() }
            bg
        }
        listOf(valid, validF, den, lowDen).forEach { it.release() }
        return result
    }

    /** Divide ogni canale per il proprio sfondo: carta bianca, niente ombre. */
    private fun normalizeIllumination(rgb: Mat): Mat {
        val chs = ArrayList<Mat>()
        Core.split(rgb, chs)
        val bgs = estimateBackgrounds(chs)
        val outChs = chs.zip(bgs).map { (ch, bg) ->
            val n = Mat()
            Core.divide(ch, bg, n, 255.0)
            bg.release(); ch.release()
            n
        }
        val out = Mat()
        Core.merge(outChs, out)
        outChs.forEach { it.release() }
        return out
    }

    private fun normalizeGray(gray: Mat): Mat {
        val bg = estimateBackgrounds(listOf(gray))[0]
        val n = Mat()
        Core.divide(gray, bg, n, 255.0)
        bg.release()
        return n
    }

    private fun magicColor(rgb: Mat): Mat {
        val n = normalizeIllumination(rgb)
        // curva: scurisce l'inchiostro e mantiene il bianco della carta
        val lut = lut { v -> 255.0 * (v / 255.0).pow(1.6) }
        Core.LUT(n, lut, n)
        lut.release()
        adjustSaturation(n, 1.35)
        return n
    }

    private fun whiten(rgb: Mat): Mat {
        val n = normalizeIllumination(rgb)
        val lut = lut { v -> 255.0 * (v / 255.0).pow(1.2) }
        Core.LUT(n, lut, n)
        lut.release()
        return n
    }

    /**
     * "Simplest Color Balance" (Limare, Lisani, Morel, Petro, Sbert — IPOL 2011):
     * stiramento per canale saturando l'1% dei pixel ai due estremi.
     * Corregge dominanti di colore (luce gialla, neon, ecc.).
     */
    private fun simplestColorBalance(rgb: Mat, saturation: Double): Mat {
        val chs = ArrayList<Mat>()
        Core.split(rgb, chs)
        val total = rgb.total().toDouble()
        for (ch in chs) {
            val hist = Mat()
            Imgproc.calcHist(listOf(ch), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
            var acc = 0.0
            var lo = 0
            while (lo < 255) { acc += hist.get(lo, 0)[0]; if (acc > total * saturation) break; lo++ }
            acc = 0.0
            var hi = 255
            while (hi > 0) { acc += hist.get(hi, 0)[0]; if (acc > total * saturation) break; hi-- }
            hist.release()
            if (hi > lo) {
                val alpha = 255.0 / (hi - lo)
                ch.convertTo(ch, -1, alpha, -lo * alpha)
            }
        }
        val out = Mat()
        Core.merge(chs, out)
        chs.forEach { it.release() }
        return out
    }

    /** CLAHE sul canale L di Lab: aumenta il contrasto locale senza alterare i colori. */
    private fun claheContrast(rgb: Mat, clip: Double): Mat {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val chs = ArrayList<Mat>()
        Core.split(lab, chs)
        val clahe = Imgproc.createCLAHE(clip, Size(8.0, 8.0))
        clahe.apply(chs[0], chs[0])
        Core.merge(chs, lab)
        chs.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2RGB)
        lab.release()
        return out
    }

    private fun vivid(rgb: Mat): Mat {
        val balanced = simplestColorBalance(rgb, 0.005)
        val out = claheContrast(balanced, 1.5)
        balanced.release()
        adjustSaturation(out, 1.5)
        return out
    }

    private fun grayEnhanced(rgb: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val n = normalizeGray(gray)
        gray.release()
        val lut = lut { v -> 255.0 * (v / 255.0).pow(1.5) }
        Core.LUT(n, lut, n)
        lut.release()
        return n
    }

    /** Bianco e nero: normalizzazione dell'illuminazione + soglia adattiva gaussiana. */
    private fun blackAndWhite(rgb: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val n = normalizeGray(gray)
        gray.release()
        Imgproc.GaussianBlur(n, n, Size(3.0, 3.0), 0.0)
        val block = oddAtLeast(min(n.cols(), n.rows()) / 40, 15)
        val out = Mat()
        Imgproc.adaptiveThreshold(
            n, out, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0
        )
        // bianco = (soglia adattiva bianca OPPURE molto chiaro) E non scuro.
        // Così aree piene scure restano nere e la carta non si sporca di rumore.
        val light = Mat()
        Imgproc.threshold(n, light, 209.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_or(out, light, out)
        val notDark = Mat()
        Imgproc.threshold(n, notDark, 139.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(out, notDark, out)
        light.release(); notDark.release()
        n.release()
        Imgproc.medianBlur(out, out, 3)
        return out
    }

    // ---------------------------------------------------------------------------------------

    private fun adjustSaturation(rgb: Mat, factor: Double) {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val chs = ArrayList<Mat>()
        Core.split(hsv, chs)
        chs[1].convertTo(chs[1], -1, factor, 0.0)
        Core.merge(chs, hsv)
        chs.forEach { it.release() }
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        hsv.release()
    }

    private fun lut(f: (Double) -> Double): Mat {
        val m = Mat(1, 256, CvType.CV_8UC1)
        val data = ByteArray(256) { f(it.toDouble()).coerceIn(0.0, 255.0).toInt().toByte() }
        m.put(0, 0, data)
        return m
    }

    private fun oddAtLeast(v: Int, minV: Int): Int {
        val x = max(v, minV)
        return if (x % 2 == 0) x + 1 else x
    }
}
