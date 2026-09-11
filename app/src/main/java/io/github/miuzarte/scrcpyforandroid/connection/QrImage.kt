package io.github.miuzarte.scrcpyforandroid.connection

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.miuzarte.scrcpyforandroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared QR-code renderer. Pure function of [payload]: encodes the string into a [Bitmap] on
 * a background dispatcher. Used by the TV pairing dialog and reusable by any other layout that
 * needs to show a scrcpy pairing QR (e.g. the phone manual-connect flow).
 */
@Composable
internal fun QrImage(payload: String, modifier: Modifier) {
    val bitmap by produceState<Bitmap?>(null, payload) {
        value = if (payload.isEmpty()) null else withContext(Dispatchers.Default) {
            val size = 480
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.tv_qr_pair), Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator()
    }
}
