/*
 * Accesa fork — Digital Euro extensions to the EUDI reference wallet.
 * SPDX-License-Identifier: EUPL-1.2
 *
 * Local copy of common-feature's QrCodeAnalyzer. Kept in de-feature so the
 * QR-config camera scanner stays decoupled from upstream's QrScanScreen flow
 * machinery (QrScanFlow / IssuanceFlowType / RQES wiring). 30 lines; a future
 * upstream rebase that changes the analyzer can be checked against this copy.
 */

package eu.europa.ec.defeature.ui.qrconfig.component

import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val supportedImageFormats = listOf(
        ImageFormat.YUV_420_888,
        ImageFormat.YUV_422_888,
        ImageFormat.YUV_444_888,
    )

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format !in supportedImageFormats) return
            val plane = image.planes.first()
            val bytes = plane.buffer.toByteArray()
            val source = PlanarYUVLuminanceSource(
                bytes,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val binaryBmp = BinaryBitmap(HybridBinarizer(source))
            val result = QRCodeReader().decode(binaryBmp)
            onQrCodeScanned(result.text)
        } catch (_: Exception) {
            // QRCodeReader throws NotFoundException on every frame without a QR.
        } finally {
            image.close()
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()
        return ByteArray(remaining()).also { get(it) }
    }
}