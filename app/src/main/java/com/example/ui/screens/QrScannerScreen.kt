package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.CloudAzure
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScannerScreen(
    onQrScanned: (String) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    val scanConsumed = remember { AtomicBoolean(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        permissionRequested = true
    }

    // Zero-permission Android Photo Picker for recognizing QR codes from screenshots/gallery
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                statusMessage = "正在识别图片中的二维码…"
                val decoded = withContext(Dispatchers.IO) { decodeQrFromGalleryUri(context, uri) }
                if (!decoded.isNullOrBlank()) {
                    if (scanConsumed.compareAndSet(false, true)) {
                        onQrScanned(decoded)
                    }
                } else {
                    statusMessage = "未在所选图片中识别到二维码，请换一张清晰的截图重试"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission && !permissionRequested) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            CameraQrPreview(
                onCameraReady = { cam -> cameraRef = cam },
                onBarcodeFound = { raw ->
                    if (scanConsumed.compareAndSet(false, true)) {
                        onQrScanned(raw)
                    }
                }
            )
            ScannerReticleOverlay()
        } else {
            // Graceful permission denied state with direct gallery fallback
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = CloudAzure.copy(alpha = 0.2f),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "需要相机权限以扫描二维码",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "您也可以直接点击下方按钮，从系统相册选取网页端账号中心的二维码截图进行登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("qr_request_camera_button")
                ) {
                    Text("授予相机权限")
                }
            }
        }

        // Top Bar overlay (Back + Title + Torch toggle)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .testTag("qr_scanner_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }

            Text(
                text = "扫一扫登录",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            if (hasCameraPermission && cameraRef?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(
                    onClick = {
                        val next = !torchEnabled
                        torchEnabled = next
                        cameraRef?.cameraControl?.enableTorch(next)
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .testTag("qr_torch_button")
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = if (torchEnabled) "关闭手电筒" else "打开手电筒",
                        tint = if (torchEnabled) Color(0xFFFDE047) else Color.White
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(48.dp))
            }
        }

        // Bottom guidance & zero-permission gallery QR button
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            statusMessage?.let { msg ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFDE68A),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "对准网页端「账号中心」头像右上角二维码或服务器地址码",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center
                )
            }

            FilledTonalButton(
                onClick = {
                    statusMessage = null
                    galleryPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("qr_pick_gallery_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("从相册选择二维码", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CameraQrPreview(
    onCameraReady: (Camera) -> Unit,
    onBarcodeFound: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@Runnable
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val reader = createQrReader()
            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val result = decodeImageProxy(imageProxy, reader)
                imageProxy.close()
                if (!result.isNullOrBlank()) {
                    ContextCompat.getMainExecutor(context).execute {
                        onBarcodeFound(result)
                    }
                }
            }

            runCatching {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                onCameraReady(camera)
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun ScannerReticleOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "qr_laser")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "qr_laser_y"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        val boxSize = (minOf(size.width, size.height) * 0.66f).coerceIn(220.dp.toPx(), 300.dp.toPx())
        val left = (size.width - boxSize) / 2f
        val top = (size.height - boxSize) / 2f - 28.dp.toPx()
        val rect = Rect(left, top, left + boxSize, top + boxSize)

        // Dimmed backdrop with transparent scan cutout
        drawRect(color = Color.Black.copy(alpha = 0.56f))
        drawRoundRect(
            color = Color.Transparent,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
            blendMode = BlendMode.Clear
        )

        // Subtle frame border
        drawRoundRect(
            color = Color.White.copy(alpha = 0.3f),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(22.dp.toPx(), 22.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )

        // Crisp Nordic Azure corner brackets
        val cornerLen = 28.dp.toPx()
        val strokeWidth = 4.dp.toPx()
        val accent = Color(0xFF38BDF8)

        fun drawCorner(start: Offset, mid: Offset, end: Offset) {
            val path = Path().apply {
                moveTo(start.x, start.y)
                lineTo(mid.x, mid.y)
                lineTo(end.x, end.y)
            }
            drawPath(
                path = path,
                color = accent,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        drawCorner(Offset(rect.left, rect.top + cornerLen), Offset(rect.left, rect.top), Offset(rect.left + cornerLen, rect.top))
        drawCorner(Offset(rect.right - cornerLen, rect.top), Offset(rect.right, rect.top), Offset(rect.right, rect.top + cornerLen))
        drawCorner(Offset(rect.left, rect.bottom - cornerLen), Offset(rect.left, rect.bottom), Offset(rect.left + cornerLen, rect.bottom))
        drawCorner(Offset(rect.right - cornerLen, rect.bottom), Offset(rect.right, rect.bottom), Offset(rect.right, rect.bottom - cornerLen))

        // Animated scan beam
        val laserY = rect.top + rect.height * laserProgress
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, accent, Color.White, accent, Color.Transparent),
                startX = rect.left + 12.dp.toPx(),
                endX = rect.right - 12.dp.toPx()
            ),
            start = Offset(rect.left + 12.dp.toPx(), laserY),
            end = Offset(rect.right - 12.dp.toPx(), laserY),
            strokeWidth = 2.5.dp.toPx()
        )
    }
}

private fun createQrReader(): MultiFormatReader {
    return MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
    }
}

private fun decodeImageProxy(imageProxy: ImageProxy, reader: MultiFormatReader): String? {
    return runCatching {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride

        // Copy tightly packed Y plane if rowStride > width
        val yBytes = if (rowStride == width) {
            data
        } else {
            ByteArray(width * height).also { out ->
                for (row in 0 until height) {
                    val srcOffset = row * rowStride
                    if (srcOffset + width <= data.size) {
                        System.arraycopy(data, srcOffset, out, row * width, width)
                    }
                }
            }
        }

        val source = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)
        decodeLuminanceWithFallbacks(source, reader)?.let { return it }

        // Fallback: 90-degree clockwise rotated Y-plane for angled/screen QR codes
        val rotated = ByteArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                rotated[x * height + (height - 1 - y)] = yBytes[rowOffset + x]
            }
        }
        val rotatedSource = PlanarYUVLuminanceSource(rotated, height, width, 0, 0, height, width, false)
        decodeLuminanceWithFallbacks(rotatedSource, reader)
    }.getOrNull().also {
        reader.reset()
    }
}

private fun decodeLuminanceWithFallbacks(
    source: com.google.zxing.LuminanceSource,
    reader: MultiFormatReader
): String? {
    runCatching {
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }
    reader.reset()
    return runCatching {
        reader.decodeWithState(BinaryBitmap(com.google.zxing.common.GlobalHistogramBinarizer(source))).text
    }.getOrNull()
}

private fun decodeQrFromGalleryUri(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, boundsOpts)
        }
        val maxDim = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        var sampleSize = 1
        while (maxDim / sampleSize > 1600) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val reader = createQrReader()
            decodeLuminanceWithFallbacks(source, reader)
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()
}
