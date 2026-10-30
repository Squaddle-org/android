@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.squaddle.app.ui.link

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.launch
import org.squaddle.app.R
import java.util.concurrent.Executors

/** `SettingsScreen`'s entry point into this flow - a plain titled row + button, same shape as
 * every other section on that screen (`SeedSection`/`BackupSection`/etc.). */
@Composable
public fun LinkDeviceSection(onLinkNewDevice: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(stringResource(R.string.settings_link_device), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.settings_link_device_explainer),
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onLinkNewDevice) { Text(stringResource(R.string.settings_link_device)) }
    }
}

/**
 * Settings -> "Link a new device" (PROTOCOL.md §2.3, master/E role): CameraX supplies frames,
 * ZXing decodes them - no Play Services dependency either way (gradle/libs.versions.toml's own
 * comment on `zxing-core`), so this is safe on the GMS-free `fdroid`/`github` flavors. Owns the
 * whole scan -> send -> confirm flow itself ([DeviceLinkViewModel]) rather than splitting state
 * with `SettingsScreen` - Navigation Compose scopes a `viewModel()` to its own nav entry, so
 * sharing one instance across routes isn't the natural option here anyway.
 */
@Composable
public fun DeviceLinkScanScreen(viewModel: DeviceLinkViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.link_scan_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                DeviceLinkUiState.Scanning -> QrScanner(onDecoded = viewModel::onQrDecoded, modifier = Modifier.fillMaxSize())
                DeviceLinkUiState.Sending -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is DeviceLinkUiState.Sent -> LinkResult(
                    title = stringResource(R.string.link_confirm_title),
                    body = stringResource(R.string.link_confirm_explainer),
                    code = current.confirmationCode,
                    actionLabel = stringResource(R.string.action_done),
                    onAction = onBack,
                )
                is DeviceLinkUiState.Failed -> LinkResult(
                    title = current.message,
                    body = null,
                    code = null,
                    actionLabel = stringResource(R.string.action_back),
                    onAction = viewModel::retry,
                )
            }
        }
    }
}

@Composable
private fun QrScanner(onDecoded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasCameraPermission) {
        CameraPreviewWithQrScan(onDecoded = onDecoded, modifier = modifier)
    } else {
        Box(modifier = modifier.padding(16.dp)) {
            Text(stringResource(R.string.link_camera_denied), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LinkResult(title: String, body: String?, code: String?, actionLabel: String, onAction: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (body != null) Text(body, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
        if (code != null) {
            Text(code, modifier = Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.displaySmall)
        }
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun CameraPreviewWithQrScan(onDecoded: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    // bindToLifecycle ties the camera to the Activity's own Lifecycle, which - this app being a
    // single-Activity Compose Navigation app - stays RESUMED across route changes; it would
    // never unbind just from the user navigating back off this screen. unbindAll() here, on this
    // Composable's own disposal, is what actually turns the camera off then (not just the
    // analyzer's executor, which is a separate resource).
    DisposableEffect(Unit) {
        onDispose {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            scope.launch {
                val cameraProvider = ProcessCameraProvider.getInstance(ctx).get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var decoded = false
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (!decoded) {
                        val text = decodeQrFrame(imageProxy)
                        if (text != null) {
                            decoded = true
                            onDecoded(text)
                        }
                    }
                    imageProxy.close()
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
            previewView
        },
    )
}

/** `null` on any frame with no decodable QR code - every call is a per-frame best-effort guess,
 * not an error. Only handles the Y (luminance) plane, same as every other ZXing-on-CameraX
 * integration - a QR code's modules only need luminance contrast, not color. */
private fun decodeQrFrame(imageProxy: ImageProxy): String? {
    if (imageProxy.format != ImageFormat.YUV_420_888) return null
    val buffer = imageProxy.planes[0].buffer
    val data = ByteArray(buffer.remaining())
    buffer.get(data)
    val source = PlanarYUVLuminanceSource(
        data, imageProxy.width, imageProxy.height, 0, 0, imageProxy.width, imageProxy.height, false,
    )
    return try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: NotFoundException) {
        null
    } catch (e: ChecksumException) {
        null
    } catch (e: FormatException) {
        null
    }
}
