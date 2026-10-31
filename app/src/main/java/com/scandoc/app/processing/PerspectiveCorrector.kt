package com.scandoc.app.processing

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Raddrizzamento del documento tramite omografia (warpPerspective con interpolazione bicubica).
 *
 * La dimensione dell'immagine di uscita non si limita alla lunghezza dei lati visibili
 * (che deformerebbe il foglio quando la foto è scattata di sbieco): il vero rapporto
 * larghezza/altezza del rettangolo viene ricostruito con il metodo di
 * Z. Zhang, L. He, "Whiteboard scanning and image enhancement" (Digital Signal Processing, 2007),
 * che stima la lunghezza focale dalla proiezione del rettangolo e ne ricava l'aspect ratio reale.
 */
object PerspectiveCorrector {

    private const val MAX_OUTPUT_SIDE = 3508 // A4 a 300 dpi

    fun warp(src: Bitmap, cornersIn: List<PointF>): Bitmap {
        val c = DocumentDetector.orderCorners(cornersIn) // TL, TR, BR, BL
        var (outW, outH) = outputSize(c, src.width, src.height)
        val longest = max(outW, outH)
        if (longest > MAX_OUTPUT_SIDE) {
            val k = MAX_OUTPUT_SIDE.toDouble() / longest
            outW = (outW * k).roundToInt()
            outH = (outH * k).roundToInt()
        }
        outW = max(outW, 10)
        outH = max(outH, 10)

        val srcPts = MatOfPoint2f(*c.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()),
            Point(0.0, outH.toDouble()),
        )
        val m = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)
        val dst = Mat()
        Imgproc.warpPerspective(
            srcMat, dst, m, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
        )
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, out)
        listOf(srcPts, dstPts, m, srcMat, dst).forEach { it.release() }
        return out
    }

    /** Calcola (larghezza, altezza) del documento raddrizzato. */
    fun outputSize(c: List<PointF>, imgW: Int, imgH: Int): Pair<Int, Int> {
        val tl = c[0]; val tr = c[1]; val br = c[2]; val bl = c[3]
        val wTop = dist(tl, tr); val wBottom = dist(bl, br)
        val hLeft = dist(tl, bl); val hRight = dist(tr, br)
        val visW = max(wTop, wBottom)
        val visH = max(hLeft, hRight)
        val visRatio = ((wTop + wBottom) / 2) / ((hLeft + hRight) / 2)

        val ratio = trueAspectRatio(tl, tr, bl, br, imgW / 2.0, imgH / 2.0)
            ?.takeIf { it in 0.15..6.5 && it / visRatio in 0.5..2.0 }
            ?: visRatio

        return if (ratio < visW / visH) {
            Pair((visH * ratio).roundToInt(), visH.roundToInt())
        } else {
            Pair(visW.roundToInt(), (visW / ratio).roundToInt())
        }
    }

    /**
     * Rapporto larghezza/altezza reale del rettangolo (Zhang & He).
     * m1=TL, m2=TR, m3=BL, m4=BR in coordinate omogenee; (u0, v0) = punto principale.
     * Restituisce null se la configurazione è degenere (es. foto quasi frontale): in tal caso
     * il rapporto visibile è già corretto.
     */
    private fun trueAspectRatio(
        tl: PointF, tr: PointF, bl: PointF, br: PointF, u0: Double, v0: Double
    ): Double? {
        val m1 = doubleArrayOf(tl.x.toDouble(), tl.y.toDouble(), 1.0)
        val m2 = doubleArrayOf(tr.x.toDouble(), tr.y.toDouble(), 1.0)
        val m3 = doubleArrayOf(bl.x.toDouble(), bl.y.toDouble(), 1.0)
        val m4 = doubleArrayOf(br.x.toDouble(), br.y.toDouble(), 1.0)

        val d2 = dot(cross(m2, m4), m3)
        val d3 = dot(cross(m3, m4), m2)
        if (kotlin.math.abs(d2) < 1e-9 || kotlin.math.abs(d3) < 1e-9) return null
        val k2 = dot(cross(m1, m4), m3) / d2
        val k3 = dot(cross(m1, m4), m2) / d3

        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }

        val n2z = n2[2]; val n3z = n3[2]
        if (kotlin.math.abs(n2z * n3z) < 1e-9) return null

        val f2 = -(
            (n2[0] * n3[0] - (n2[0] * n3z + n2z * n3[0]) * u0 + n2z * n3z * u0 * u0) +
                (n2[1] * n3[1] - (n2[1] * n3z + n2z * n3[1]) * v0 + n2z * n3z * v0 * v0)
            ) / (n2z * n3z)
        if (f2 <= 0 || f2.isNaN()) return null
        val f = sqrt(f2)

        // n^T (A^-T A^-1) n = |A^-1 n|^2 con A = [[f,0,u0],[0,f,v0],[0,0,1]]
        fun normSq(n: DoubleArray): Double {
            val a = (n[0] - u0 * n[2]) / f
            val b = (n[1] - v0 * n[2]) / f
            return a * a + b * b + n[2] * n[2]
        }
        val r = sqrt(normSq(n2) / normSq(n3))
        return if (r.isNaN() || r.isInfinite()) null else r
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun dist(a: PointF, b: PointF) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
}
