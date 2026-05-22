/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Full-screen modal camera preview that decodes one QR code and reports it
 * back. Used by the first-launch QR-config screen so workshop participants
 * can point the wallet at the pid-issuer URL without typing.
 *
 * Kept inside de-feature (rather than reusing upstream's QrScanScreen) so the
 * Accesa flow stays decoupled from upstream's QrScanFlow / IssuanceFlowType
 * routing. The CameraX preview boilerplate mirrors common-feature's OpenCamera
 * composable.
 */

package eu.europa.ec.defeature.ui.qrconfig.component

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraQrPickerDialog(
    onQrScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        val permissionState =
            rememberPermissionState(permission = android.Manifest.permission.CAMERA)

        LaunchedEffect(Unit) {
            if (!permissionState.status.isGranted &&
                !permissionState.status.shouldShowRationale
            ) {
                permissionState.launchPermissionRequest()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            when {
                permissionState.status.isGranted -> CameraSurface(
                    onQrScanned = onQrScanned,
                )

                permissionState.status.shouldShowRationale -> PermissionRationale(
                    onRetry = { permissionState.launchPermissionRequest() },
                    onDismiss = onDismiss,
                )

                else -> {
                    // Initial request in flight; show a black holding screen with a Cancel
                    // affordance so the user can always back out.
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Text("Cancel", color = Color.White)
            }

            Text(
                text = "Point the camera at the workshop QR",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
            )
        }
    }
}

@Composable
private fun CameraSurface(
    onQrScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val preview = Preview.Builder().build()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
                preview.surfaceProvider = previewView.surfaceProvider

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(
                    analysisExecutor,
                    QrCodeAnalyzer { result ->
                        mainExecutor.execute { onQrScanned(result) }
                    },
                )

                try {
                    cameraProviderFuture.get().bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageAnalysis,
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                previewView
            },
        )

        Canvas(modifier = Modifier.size(240.dp)) {
            val stroke = 3.dp.toPx()
            val capLen = 24.dp.toPx()
            val w = size.width
            val h = size.height
            // Four L-shaped corner brackets, like upstream's QR reticle.
            val brackets = listOf(
                // top-left
                listOf(0f to 0f, capLen to 0f) to listOf(0f to 0f, 0f to capLen),
                // top-right
                listOf(w - capLen to 0f, w to 0f) to listOf(w to 0f, w to capLen),
                // bottom-left
                listOf(0f to h - capLen, 0f to h) to listOf(0f to h, capLen to h),
                // bottom-right
                listOf(w to h - capLen, w to h) to listOf(w - capLen to h, w to h),
            )
            brackets.forEach { (a, b) ->
                drawLine(
                    color = Color.White,
                    start = androidx.compose.ui.geometry.Offset(a[0].first, a[0].second),
                    end = androidx.compose.ui.geometry.Offset(a[1].first, a[1].second),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = Color.White,
                    start = androidx.compose.ui.geometry.Offset(b[0].first, b[0].second),
                    end = androidx.compose.ui.geometry.Offset(b[1].first, b[1].second),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
        }
    }
}

@Composable
private fun PermissionRationale(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Camera access is needed to scan the workshop QR.",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) { Text("Grant camera access", color = Color.White) }
        TextButton(onClick = onDismiss) { Text("Enter URL manually", color = Color.White) }
    }
}
