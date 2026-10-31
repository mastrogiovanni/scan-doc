package com.scandoc.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scandoc.app.ScanViewModel

@Composable
fun CameraScreen(vm: ScanViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(Manifest.permission.CAMERA) }
    BackHandler { vm.backToContext() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) CameraContent(vm)
        else Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Serve il permesso della fotocamera per scansionare i documenti.", color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("Concedi permesso") }
        }
        IconButton(
            onClick = { vm.backToContext() },
            modifier = Modifier.systemBarsPadding().padding(8.dp).align(Alignment.TopStart)
        ) { Icon(Icons.Default.Close, "Chiudi", tint = Color.White) }
    }
}

@Composable
private fun CameraContent(vm: ScanViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var flash by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val ratio = remember {
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ratio)
            .build()
    }
    imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            provider = future.get()
            val preview = Preview.Builder().setResolutionSelector(ratio).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                vm.message = "Fotocamera non disponibile"
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { provider?.unbindAll() }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        vm.importFromGallery(uris)
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Text(
                "Inquadra il foglio su uno sfondo contrastante",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.size(56.dp)
            ) { Icon(Icons.Default.PhotoLibrary, "Galleria", tint = Color.White, modifier = Modifier.size(30.dp)) }

            // pulsante di scatto
            Box(
                Modifier
                    .size(78.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (capturing) Color.Gray else Color.White)
                    .clickable(enabled = !capturing) {
                        capturing = true
                        val file = vm.newPhotoFile()
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    capturing = false
                                    vm.onPhotoCaptured(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                    vm.message = "Errore durante lo scatto"
                                }
                            }
                        )
                    }
            )

            IconButton(onClick = { flash = !flash }, modifier = Modifier.size(56.dp)) {
                Icon(
                    if (flash) Icons.Default.FlashOn else Icons.Default.FlashOff, "Flash",
                    tint = if (flash) Accent else Color.White, modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}
