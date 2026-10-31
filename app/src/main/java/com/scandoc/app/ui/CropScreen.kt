package com.scandoc.app.ui

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.scandoc.app.ScanViewModel
import com.scandoc.app.processing.ImageUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val InvalidColor = Color(0xFFE53935)

@Composable
fun CropScreen(vm: ScanViewModel) {
    val e = vm.edit ?: return
    BackHandler { vm.discardEdit() }
    val valid = isConvexQuad(e.corners)

    Column(Modifier.fillMaxSize().background(Color(0xFF111111)).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.discardEdit() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Annulla", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("Regola i bordi", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (valid) "Trascina gli angoli o i lati" else "Forma non valida: correggi gli angoli",
                    color = if (valid) Color.LightGray else InvalidColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        CornerEditor(
            bitmap = e.original,
            corners = e.corners,
            valid = valid,
            onChange = vm::setCorners,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = vm::useDetectedCorners) {
                Icon(Icons.Default.AutoFixHigh, null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Auto", color = Color.White)
            }
            TextButton(onClick = vm::useFullImage) {
                Icon(Icons.Default.CropFree, null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Tutta", color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = vm::confirmCrop, enabled = valid, modifier = Modifier.height(48.dp)) {
                Text("Avanti")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
            }
        }
    }
}

/**
 * Mostra l'immagine con il quadrilatero del documento.
 * 4 maniglie sugli angoli + 4 sui lati (spostano un intero lato); durante il trascinamento
 * di un angolo compare una lente d'ingrandimento per il posizionamento preciso.
 */
@Composable
fun CornerEditor(
    bitmap: Bitmap,
    corners: List<PointF>,
    valid: Boolean,
    onChange: (List<PointF>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // per la visualizzazione si usa una copia ridotta (texture GPU leggera)
    val display = remember(bitmap) { ImageUtils.scaleDown(bitmap, 2048).asImageBitmap() }
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()
    val dispFactor = display.width / imgW

    val primary = MaterialTheme.colorScheme.primary
    val lineColor = if (valid) primary else InvalidColor
    val density = LocalDensity.current
    val currentCorners by rememberUpdatedState(corners)
    val currentOnChange by rememberUpdatedState(onChange)
    var active by remember { mutableIntStateOf(-1) }

    BoxWithConstraints(modifier) {
        val pad = with(density) { 28.dp.toPx() }
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val scale = min((cw - 2 * pad) / imgW, (ch - 2 * pad) / imgH)
        val ox = (cw - imgW * scale) / 2
        val oy = (ch - imgH * scale) / 2
        fun toScreen(p: PointF) = Offset(ox + p.x * scale, oy + p.y * scale)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(scale, ox, oy) {
                    val touchR = 44.dp.toPx()
                    detectDragGestures(
                        onDragStart = { pos ->
                            val pts = currentCorners.map(::toScreen)
                            var best = -1
                            var bestD = touchR
                            pts.forEachIndexed { i, p ->
                                val d = (p - pos).getDistance()
                                if (d < bestD) { bestD = d; best = i }
                            }
                            if (best < 0) {
                                for (i in 0 until 4) {
                                    val mid = (pts[i] + pts[(i + 1) % 4]) / 2f
                                    val d = (mid - pos).getDistance()
                                    if (d < bestD) { bestD = d; best = 4 + i }
                                }
                            }
                            active = best
                        },
                        onDrag = { change, drag ->
                            if (active >= 0) {
                                change.consume()
                                val dx = drag.x / scale
                                val dy = drag.y / scale
                                val c = currentCorners.map { PointF(it.x, it.y) }.toMutableList()
                                fun move(i: Int) {
                                    c[i] = PointF((c[i].x + dx).coerceIn(0f, imgW), (c[i].y + dy).coerceIn(0f, imgH))
                                }
                                if (active < 4) move(active) else { move(active - 4); move((active - 3) % 4) }
                                currentOnChange(c)
                            }
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 }
                    )
                }
        ) {
            val imgRect = Rect(Offset(ox, oy), Size(imgW * scale, imgH * scale))
            drawImage(
                display,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(imgRect.width.roundToInt(), imgRect.height.roundToInt()),
                filterQuality = FilterQuality.Medium
            )
            val pts = corners.map(::toScreen)
            val quad = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until 4) lineTo(pts[i].x, pts[i].y)
                close()
            }
            // oscura l'area esterna al documento
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(imgRect)
                addPath(quad)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.45f))
            drawPath(quad, lineColor.copy(alpha = 0.12f))
            drawPath(quad, lineColor, style = Stroke(width = 2.5.dp.toPx()))

            // maniglie sui lati
            for (i in 0 until 4) {
                val mid = (pts[i] + pts[(i + 1) % 4]) / 2f
                drawCircle(Color.White, 7.dp.toPx(), mid)
                drawCircle(lineColor, 7.dp.toPx(), mid, style = Stroke(2.dp.toPx()))
            }
            // maniglie sugli angoli
            pts.forEachIndexed { i, p ->
                val r = if (active == i) 15.dp.toPx() else 12.dp.toPx()
                drawCircle(lineColor.copy(alpha = 0.25f), r + 8.dp.toPx(), p)
                drawCircle(Color.White, r, p)
                drawCircle(lineColor, r, p, style = Stroke(3.dp.toPx()))
            }

            // lente d'ingrandimento
            if (active in 0..3) {
                val corner = corners[active]
                val p = pts[active]
                val radius = 58.dp.toPx()
                val zoom = 2.5f
                val margin = 12.dp.toPx()
                var center = Offset(margin + radius, margin + radius)
                if ((p - center).getDistance() < radius * 2.2f) center = Offset(size.width - margin - radius, margin + radius)
                val loupeTL = center - Offset(radius, radius)

                // regione sorgente in coordinate dell'immagine originale
                val half = radius / (scale * zoom)
                val srcL = (corner.x - half).coerceIn(0f, max(0f, imgW - 2 * half))
                val srcT = (corner.y - half).coerceIn(0f, max(0f, imgH - 2 * half))
                val srcW = min(2 * half, imgW)
                val srcH = min(2 * half, imgH)
                val k = 2 * radius / (2 * half)
                fun toLoupe(q: PointF) = Offset(loupeTL.x + (q.x - srcL) * k, loupeTL.y + (q.y - srcT) * k)

                val circle = Path().apply { addOval(Rect(center, radius)) }
                clipPath(circle) {
                    drawRect(Color.Black, loupeTL, Size(2 * radius, 2 * radius))
                    drawImage(
                        display,
                        srcOffset = IntOffset((srcL * dispFactor).roundToInt(), (srcT * dispFactor).roundToInt()),
                        srcSize = IntSize(
                            max(1, (srcW * dispFactor).roundToInt()).coerceAtMost(display.width - (srcL * dispFactor).roundToInt()),
                            max(1, (srcH * dispFactor).roundToInt()).coerceAtMost(display.height - (srcT * dispFactor).roundToInt())
                        ),
                        dstOffset = IntOffset(loupeTL.x.roundToInt(), loupeTL.y.roundToInt()),
                        dstSize = IntSize((srcW * k).roundToInt(), (srcH * k).roundToInt()),
                        filterQuality = FilterQuality.Medium
                    )
                    val cp = toLoupe(corner)
                    drawLine(lineColor, cp, toLoupe(corners[(active + 1) % 4]), 2.dp.toPx())
                    drawLine(lineColor, cp, toLoupe(corners[(active + 3) % 4]), 2.dp.toPx())
                    val cr = 10.dp.toPx()
                    drawLine(Accent, cp - Offset(cr, 0f), cp + Offset(cr, 0f), 1.5.dp.toPx())
                    drawLine(Accent, cp - Offset(0f, cr), cp + Offset(0f, cr), 1.5.dp.toPx())
                }
                drawCircle(Color.White, radius, center, style = Stroke(3.dp.toPx()))
            }
        }
    }
}

fun isConvexQuad(p: List<PointF>): Boolean {
    if (p.size != 4) return false
    var sign = 0
    for (i in 0 until 4) {
        val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        val s = if (cross > 0) 1 else if (cross < 0) -1 else return false
        if (sign == 0) sign = s else if (s != sign) return false
    }
    // area minima: almeno 1% dell'inviluppo dei punti
    val area = kotlin.math.abs(
        (0 until 4).sumOf { i -> (p[i].x * p[(i + 1) % 4].y - p[(i + 1) % 4].x * p[i].y).toDouble() }
    ) / 2
    return area > 100
}
