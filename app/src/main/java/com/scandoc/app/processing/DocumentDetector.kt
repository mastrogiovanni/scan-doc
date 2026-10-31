package com.scandoc.app.processing

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rilevamento automatico dei 4 angoli di un documento.
 *
 * Pipeline (multi-strategia, si sceglie il candidato con punteggio migliore):
 *  1. ridimensionamento a ~720px per velocità;
 *  2. su più canali (luminanza, saturazione, L di Lab) si applica una chiusura morfologica
 *     che "cancella" il testo stampato, lasciando solo il contorno del foglio;
 *  3. si ricavano mappe binarie con Canny (soglie automatiche dalla mediana + soglie fisse)
 *     e con soglia di Otsu;
 *  4. per ogni contorno grande si calcola l'inviluppo convesso e lo si approssima a un
 *     quadrilatero (approxPolyDP con epsilon crescente, oppure quadrilatero di area massima
 *     inscritto nell'inviluppo);
 *  5. ogni candidato riceve un punteggio = area × supporto dei bordi (percentuale del perimetro
 *     che giace effettivamente su un bordo dell'immagine), con penalità per angoli irrealistici;
 *  6. il miglior quadrilatero viene raffinato a risoluzione piena con fitLine robusta (Huber)
 *     sui pixel di bordo vicini a ciascun lato, intersecando poi i 4 lati.
 */
object DocumentDetector {

    private const val WORK_SIZE = 720.0
    private const val REFINE_SIZE = 2000.0

    private data class Candidate(val quad: List<Point>, val score: Double)

    /** Restituisce i 4 angoli (TL, TR, BR, BL) in coordinate del bitmap, oppure null. */
    fun detect(bitmap: Bitmap): List<PointF>? {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        val scale = min(1.0, WORK_SIZE / max(rgb.cols(), rgb.rows()))
        val small = Mat()
        Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)

        val quad = try {
            findBestQuad(small)
        } finally {
            small.release()
        }
        if (quad == null) {
            rgb.release()
            return null
        }

        val full = quad.map { Point(it.x / scale, it.y / scale) }
        val refined = try {
            refineCorners(rgb, full)
        } catch (_: Throwable) {
            full
        } finally {
            rgb.release()
        }
        return orderCorners(refined.map { PointF(it.x.toFloat(), it.y.toFloat()) })
    }

    // ---------------------------------------------------------------------------------------

    private fun findBestQuad(rgb: Mat): List<Point>? {
        val w = rgb.cols()
        val h = rgb.rows()
        val imgArea = (w * h).toDouble()

        // Canali da analizzare
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val hsvCh = ArrayList<Mat>()
        Core.split(hsv, hsvCh)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val labCh = ArrayList<Mat>()
        Core.split(lab, labCh)
        val channels = listOf(gray, hsvCh[1], labCh[1], labCh[2])

        val candidates = ArrayList<Candidate>()
        val allEdges = Mat.zeros(h, w, CvType.CV_8UC1)
        val kernelClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val binaries = ArrayList<Mat>()

        for ((idx, ch) in channels.withIndex()) {
            val blurred = Mat()
            Imgproc.GaussianBlur(ch, blurred, Size(5.0, 5.0), 0.0)
            // la chiusura elimina testo e dettagli scuri sottili all'interno del foglio
            val closed = Mat()
            Imgproc.morphologyEx(blurred, closed, Imgproc.MORPH_CLOSE, kernelClose, Point(-1.0, -1.0), 2)
            blurred.release()

            val med = median(closed)
            val thresholds = listOf(
                Pair(max(5.0, 0.66 * med), min(255.0, max(20.0, 1.33 * med))),
                Pair(20.0, 60.0),
                Pair(50.0, 150.0),
            )
            for ((lo, hi) in thresholds) {
                val edges = Mat()
                Imgproc.Canny(closed, edges, lo, hi)
                Imgproc.dilate(edges, edges, kernel3)
                Core.bitwise_or(allEdges, edges, allEdges)
                binaries.add(edges)
            }
            if (idx == 0) {
                val otsu = Mat()
                Imgproc.threshold(closed, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_OPEN, kernelClose)
                binaries.add(otsu)
            }
            closed.release()
        }

        for (bin in binaries) {
            collectQuads(bin, imgArea, w, h, candidates)
            bin.release()
        }

        // Punteggio finale con supporto dei bordi
        var best: Candidate? = null
        for (c in candidates) {
            val support = edgeSupport(allEdges, c.quad)
            val s = c.score * (0.25 + support)
            if (support > 0.35 && (best == null || s > best.score)) best = Candidate(c.quad, s)
        }

        listOf(gray, hsv, lab, allEdges, kernelClose, kernel3).forEach { it.release() }
        hsvCh.forEach { it.release() }
        labCh.forEach { it.release() }
        return best?.quad
    }

    private fun collectQuads(bin: Mat, imgArea: Double, w: Int, h: Int, out: MutableList<Candidate>) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(bin.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)
        for (c in sorted) {
            val area = Imgproc.contourArea(c)
            if (area < imgArea * 0.08) break

            val hullIdx = MatOfInt()
            Imgproc.convexHull(c, hullIdx)
            val pts = c.toArray()
            val hullPts = hullIdx.toArray().map { pts[it] }
            hullIdx.release()
            if (hullPts.size < 4) continue
            val hull2f = MatOfPoint2f(*hullPts.toTypedArray())
            val peri = Imgproc.arcLength(hull2f, true)

            var quad: List<Point>? = null
            var penalty = 1.0
            for (eps in doubleArrayOf(0.015, 0.02, 0.03, 0.04, 0.05, 0.07)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
                val a = approx.toArray()
                approx.release()
                if (a.size == 4) {
                    quad = a.toList(); break
                }
                if (a.size < 4) break
            }
            if (quad == null) {
                // quadrilatero di area massima inscritto nell'inviluppo
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(hull2f, approx, 0.01 * peri, true)
                val a = approx.toArray()
                approx.release()
                if (a.size in 4..40) {
                    quad = maxAreaQuad(a.toList())
                    penalty = 0.85
                }
            }
            hull2f.release()
            c.release()
            if (quad == null) continue

            val qArea = polygonArea(quad)
            val frac = qArea / imgArea
            if (frac < 0.08 || frac > 0.995) continue
            if (!isConvex(quad)) continue
            val angleOk = interiorAngles(quad).all { it in 35.0..145.0 }
            if (!angleOk) continue
            // penalizza quadrilateri incollati al bordo dell'immagine su più lati (spesso falsi)
            val margin = 0.01 * max(w, h)
            val onBorder = quad.count { it.x < margin || it.y < margin || it.x > w - margin || it.y > h - margin }
            if (onBorder >= 3) penalty *= 0.6
            out.add(Candidate(quad, frac * penalty))
        }
        contours.forEach { it.release() }
    }

    /** Percentuale di punti campionati lungo il perimetro che cadono su un bordo rilevato. */
    private fun edgeSupport(edges: Mat, quad: List<Point>): Double {
        var hit = 0
        var total = 0
        val w = edges.cols()
        val h = edges.rows()
        val buf = ByteArray(1)
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            val steps = max(10, (len / 3).toInt())
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val x = (a.x + (b.x - a.x) * t).toInt()
                val y = (a.y + (b.y - a.y) * t).toInt()
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                total++
                edges.get(y, x, buf)
                if (buf[0].toInt() != 0) hit++
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    private fun maxAreaQuad(p: List<Point>): List<Point> {
        val n = p.size
        var best = doubleArrayOf(-1.0)
        var res = listOf(p[0], p[1], p[2], p[3])
        for (i in 0 until n) for (j in i + 1 until n) for (k in j + 1 until n) for (l in k + 1 until n) {
            val q = listOf(p[i], p[j], p[k], p[l])
            val a = polygonArea(q)
            if (a > best[0]) {
                best[0] = a; res = q
            }
        }
        return res
    }

    // ---------------------------------------------------------------------------------------
    // Raffinamento sub-pixel dei lati a risoluzione elevata

    private fun refineCorners(rgbFull: Mat, quad: List<Point>): List<Point> {
        val s = min(1.0, REFINE_SIZE / max(rgbFull.cols(), rgbFull.rows()))
        val img = Mat()
        Imgproc.resize(rgbFull, img, Size(), s, s, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_RGB2GRAY)
        img.release()
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
        Imgproc.morphologyEx(gray, gray, Imgproc.MORPH_CLOSE, k)
        k.release()
        val med = median(gray)
        val q = quad.map { Point(it.x * s, it.y * s) }
        // prima soglie adattive, poi soglie basse per sfondi chiari a basso contrasto
        val thresholds = listOf(Pair(max(10.0, 0.5 * med), max(30.0, 1.2 * med)), Pair(15.0, 40.0), Pair(8.0, 24.0))
        try {
            for ((lo, hi) in thresholds) {
                val edges = Mat()
                Imgproc.Canny(gray, edges, lo, hi)
                val res = try { fitQuadLines(edges, q) } finally { edges.release() }
                if (res != null) return res.map { Point(it.x / s, it.y / s) }
            }
        } finally {
            gray.release()
        }
        return quad
    }

    /** Ritorna gli angoli raffinati (stessa scala di [q]) oppure null se non affidabili. */
    private fun fitQuadLines(edges: Mat, q: List<Point>): List<Point>? {

        val diag = hypot(edges.cols().toDouble(), edges.rows().toDouble())
        val band = max(6.0, diag * 0.012)

        val lines = ArrayList<DoubleArray>() // (vx, vy, x0, y0)
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val mask = Mat.zeros(edges.size(), CvType.CV_8UC1)
            // si escludono le zone vicine agli angoli (dove i lati si mescolano)
            val ax = a.x + (b.x - a.x) * 0.08
            val ay = a.y + (b.y - a.y) * 0.08
            val bx = a.x + (b.x - a.x) * 0.92
            val by = a.y + (b.y - a.y) * 0.92
            Imgproc.line(mask, Point(ax, ay), Point(bx, by), Scalar(255.0), (band * 2).toInt())
            Core.bitwise_and(mask, edges, mask)
            val nz = Mat()
            Core.findNonZero(mask, nz)
            mask.release()
            val len = hypot(b.x - a.x, b.y - a.y)
            if (nz.rows() < len * 0.3) {
                nz.release(); return null
            }
            val pts = MatOfPoint2f()
            nz.convertTo(pts, CvType.CV_32FC2)
            nz.release()
            val line = Mat()
            Imgproc.fitLine(pts, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            pts.release()
            val l = DoubleArray(4) { line.get(it, 0)[0] }
            line.release()
            // il lato stimato deve essere quasi parallelo a quello iniziale
            val dx = (b.x - a.x) / len
            val dy = (b.y - a.y) / len
            if (abs(dx * l[0] + dy * l[1]) < 0.985) return null
            lines.add(l)
        }
        val res = ArrayList<Point>()
        for (i in 0 until 4) {
            // angolo i = intersezione del lato (i-1) con il lato i
            val p = intersect(lines[(i + 3) % 4], lines[i]) ?: return null
            val orig = q[i]
            if (hypot(p.x - orig.x, p.y - orig.y) > band * 2.5) return null
            res.add(p)
        }
        return if (isConvex(res)) res else null
    }

    private fun intersect(l1: DoubleArray, l2: DoubleArray): Point? {
        val (vx1, vy1, x1, y1) = l1.toList()
        val (vx2, vy2, x2, y2) = l2.toList()
        val det = vx1 * vy2 - vy1 * vx2
        if (abs(det) < 1e-6) return null
        val t = ((x2 - x1) * vy2 - (y2 - y1) * vx2) / det
        return Point(x1 + vx1 * t, y1 + vy1 * t)
    }

    // ---------------------------------------------------------------------------------------
    // Utility geometriche

    private fun median(m: Mat): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(m), MatOfInt(0), Mat(), hist, MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val total = m.total().toDouble()
        var acc = 0.0
        for (i in 0 until 256) {
            acc += hist.get(i, 0)[0]
            if (acc >= total / 2) {
                hist.release(); return i.toDouble()
            }
        }
        hist.release()
        return 127.0
    }

    fun polygonArea(p: List<Point>): Double {
        var a = 0.0
        for (i in p.indices) {
            val j = (i + 1) % p.size
            a += p[i].x * p[j].y - p[j].x * p[i].y
        }
        return abs(a) / 2
    }

    private fun isConvex(p: List<Point>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun interiorAngles(p: List<Point>): List<Double> = (0 until 4).map { i ->
        val prev = p[(i + 3) % 4]; val cur = p[i]; val next = p[(i + 1) % 4]
        val v1x = prev.x - cur.x; val v1y = prev.y - cur.y
        val v2x = next.x - cur.x; val v2y = next.y - cur.y
        val cos = (v1x * v2x + v1y * v2y) / (sqrt(v1x * v1x + v1y * v1y) * sqrt(v2x * v2x + v2y * v2y))
        Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
    }

    /** Ordina 4 punti in TL, TR, BR, BL (robusto anche per quadrilateri molto inclinati). */
    fun orderCorners(pts: List<PointF>): List<PointF> {
        val cx = pts.map { it.x }.average().toFloat()
        val cy = pts.map { it.y }.average().toFloat()
        // ordine orario attorno al baricentro (asse y verso il basso)
        val sorted = pts.sortedBy { kotlin.math.atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        // il primo deve essere il top-left: minima somma x+y
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }
    }

    /** Quadrilatero predefinito (margine del 8%) usato quando il rilevamento fallisce. */
    fun defaultCorners(w: Int, h: Int): List<PointF> {
        val mx = w * 0.08f
        val my = h * 0.08f
        return listOf(PointF(mx, my), PointF(w - mx, my), PointF(w - mx, h - my), PointF(mx, h - my))
    }

    fun fullCorners(w: Int, h: Int): List<PointF> =
        listOf(PointF(0f, 0f), PointF(w.toFloat(), 0f), PointF(w.toFloat(), h.toFloat()), PointF(0f, h.toFloat()))
}
