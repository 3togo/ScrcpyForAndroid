package io.github.miuzarte.scrcpyforandroid.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.miuzarte.scrcpyforandroid.R
import io.github.miuzarte.scrcpyforandroid.connection.StatusLine
import io.github.miuzarte.scrcpyforandroid.constants.UiSpacing
import io.github.miuzarte.scrcpyforandroid.ui.contextClick
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "QrScanner"
private const val CAMERA_READY_TIMEOUT_MS = 8_000L

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * 摄像头扫码对话框。
 *
 * 只负责取景与解码; 结果分类与后续动作交给 [classifyScannedQr] 与调用方, 与
 * [io.github.miuzarte.scrcpyforandroid.connection.runQrPairing] 相同的"UI 薄、逻辑可离线测"分层。
 */
@Composable
internal fun QrScanDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    // 每次打开都重新判断相机是否可用, 避免上次失败的状态残留
    var cameraUnavailable by remember(show) { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        granted = it
        denied = !it
    }

    LaunchedEffect(show) {
        if (show && !granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    OverlayDialog(
        show = show,
        title = stringResource(R.string.device_scan_qr_title),
        summary = stringResource(R.string.device_scan_qr_desc),
        defaultWindowInsetsPadding = false,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(UiSpacing.ContentVertical),
        ) {
            if (granted) {
                // 高度受对话框内容区限制, 取 220.dp 以免压住标题与下方提示行
                Box(Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clipToBounds()) {
                    CameraPreview(
                        active = show,
                        onUnavailable = { cameraUnavailable = true },
                        onScanned = { text ->
                            haptic.contextClick()
                            onScanned(text)
                        },
                    )
                }
                StatusLine(
                    text = stringResource(
                        if (cameraUnavailable) R.string.device_scan_qr_failed else R.string.device_scan_qr_hint,
                    ),
                    isError = cameraUnavailable,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 权限请求中与被拒绝共用一处状态行, 拒绝时以错误色提示
                StatusLine(
                    stringResource(
                        if (denied) R.string.device_scan_qr_denied else R.string.device_scan_qr_starting,
                    ),
                    isError = denied,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(
                text = stringResource(R.string.button_cancel),
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 预览 + 逐帧解码。
 *
 * 解码在单线程后台执行器上进行, 命中一次后 [AtomicBoolean] 拦住后续帧, 回一次结果即停止;
 * 绑定成功后协程挂在 [awaitCancellation] 上, 直到离开组合 (关闭对话框) 才解绑相机,
 * 否则预览刚建立就会被 finally 释放。
 */
@Composable
private fun CameraPreview(
    active: Boolean,
    onUnavailable: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onScannedLatest by rememberUpdatedState(onScanned)
    val onUnavailableLatest by rememberUpdatedState(onUnavailable)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // COMPATIBLE = TextureView: 默认 PERFORMANCE 的 SurfaceView 内容不会出现在
            // 录屏与 scrcpy 镜像里, 而本应用的主要使用场景正是被镜像的屏幕
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect

        val consumed = AtomicBoolean(false)
        val acceptingResults = AtomicBoolean(true)
        val loggedFrame = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        var provider: ProcessCameraProvider? = null
        try {
            val ready = withTimeoutOrNull(CAMERA_READY_TIMEOUT_MS) { cameraProvider(context) }
            if (ready == null) {
                Log.w(TAG, "camera provider not ready within $CAMERA_READY_TIMEOUT_MS ms")
                onUnavailableLatest()
                return@LaunchedEffect
            }
            provider = ready
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(executor) { image ->
                        val text = try {
                            if (loggedFrame.compareAndSet(false, true)) {
                                Log.i(TAG, "first analysis frame ${image.width}x${image.height}")
                            }
                            if (consumed.get()) null else decodeQr(reader, image)
                        } finally {
                            image.close()
                        }
                        if (!text.isNullOrEmpty() && consumed.compareAndSet(false, true)) {
                            // 只记长度与前缀: 配对码载荷含密钥, 不得写入日志
                            Log.i(TAG, "decoded ${text.length} chars, starts with ${text.take(5)}")
                            // 分析线程无协程, 借 View.post 回主线程派发结果
                            previewView.post {
                                if (acceptingResults.get()) onScannedLatest(text)
                            }
                        }
                    }
                }
            val bound = runCatching {
                ready.unbindAll()
                ready.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure { Log.w(TAG, "camera bind failed", it) }.isSuccess
            if (!bound) {
                onUnavailableLatest()
                return@LaunchedEffect
            }
            Log.i(TAG, "camera bound, preview ${previewView.width}x${previewView.height}")
            awaitCancellation()
        } finally {
            acceptingResults.set(false)
            withContext(NonCancellable) {
                runCatching { provider?.unbindAll() }
            }
            executor.shutdownNow()
        }
    }
}

private suspend fun cameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val result = runCatching { future.get() }
            if (!continuation.isActive) return@addListener
            result.getOrNull()?.let { continuation.resume(it) }
                ?: continuation.resumeWithException(
                    result.exceptionOrNull()
                        ?: IllegalStateException("Camera provider returned no instance"),
                )
        }, ContextCompat.getMainExecutor(context))
    }

/**
 * 从 YUV_420_888 的亮度平面解码二维码。
 *
 * zxing 的二维码定位对旋转不敏感, 因此不做方向矫正; 行跨距需按 rowStride 压缩成紧凑数组,
 * 否则部分设备 (rowStride > width) 会解出错位图像。
 */
private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? = runCatching {
    val width = image.width
    val height = image.height
    val plane = image.planes.firstOrNull() ?: return@runCatching null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val luminance = ByteArray(width * height)

    var row = 0
    while (row < height) {
        val start = row * rowStride
        if (start >= buffer.limit()) break
        buffer.position(start)
        buffer.get(luminance, row * width, minOf(width, buffer.limit() - start))
        row++
    }

    val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
    reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
}.getOrNull().also { reader.reset() }
