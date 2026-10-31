package com.scandoc.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scandoc.app.ScanViewModel
import com.scandoc.app.processing.FilterType

@Composable
fun FilterScreen(vm: ScanViewModel) {
    val e = vm.edit ?: return
    BackHandler { vm.backToCrop() }

    Column(Modifier.fillMaxSize().background(Color(0xFF111111)).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::backToCrop) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro", tint = Color.White)
            }
            Text(
                "Scegli un filtro", color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = vm::rotateResult) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, "Ruota", tint = Color.White)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val bmp = e.filteredPreview ?: e.preview
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(FilterType.entries) { f ->
                FilterThumb(
                    label = f.label,
                    selected = e.filter == f,
                    thumb = e.thumbnails[f],
                    onClick = { vm.selectFilter(f) }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = { vm.savePage(thenCamera = true) },
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Icon(Icons.Default.AddAPhoto, null)
                Spacer(Modifier.width(6.dp))
                Text("Salva + altra", maxLines = 1)
            }
            Button(
                onClick = { vm.savePage(thenCamera = false) },
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(6.dp))
                Text("Salva", maxLines = 1)
            }
        }
    }
}

@Composable
private fun FilterThumb(label: String, selected: Boolean, thumb: android.graphics.Bitmap?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(76.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(width = 72.dp, height = 92.dp)
                .clip(shape)
                .background(Color(0xFF2A2A2A))
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.DarkGray, shape),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                val img = remember(thumb) { thumb.asImageBitmap() }
                Image(img, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Crop)
            } else {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Gray)
            }
        }
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.LightGray,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp).height(32.dp)
        )
    }
}
