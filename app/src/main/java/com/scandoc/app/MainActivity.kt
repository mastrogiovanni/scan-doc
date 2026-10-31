package com.scandoc.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.scandoc.app.pdf.PdfBuilder
import com.scandoc.app.ui.AppTheme
import com.scandoc.app.ui.CameraScreen
import com.scandoc.app.ui.CropScreen
import com.scandoc.app.ui.FilterScreen
import com.scandoc.app.ui.DocumentScreen
import com.scandoc.app.ui.DocumentsScreen
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {

    private val vm: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "Impossibile inizializzare OpenCV", Toast.LENGTH_LONG).show()
        }
        setContent {
            AppTheme {
                App(vm)
            }
        }
    }

    @Composable
    private fun App(vm: ScanViewModel) {
        val share = vm.pendingShare
        LaunchedEffect(share) {
            if (share != null) {
                vm.pendingShare = null
                PdfBuilder.share(this@MainActivity, share.first, share.second)
            }
        }
        val msg = vm.message
        LaunchedEffect(msg) {
            if (msg != null) {
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                vm.message = null
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (vm.screen) {
                Screen.DOCUMENTS -> DocumentsScreen(vm)
                Screen.DOCUMENT -> DocumentScreen(vm)
                Screen.CAMERA -> CameraScreen(vm)
                Screen.CROP -> CropScreen(vm)
                Screen.FILTER -> FilterScreen(vm)
            }
            vm.busy?.let { BusyOverlay(it) }
        }
    }
}

@Composable
private fun BusyOverlay(text: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            // blocca i tocchi sotto l'overlay
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
        }
    }
}
