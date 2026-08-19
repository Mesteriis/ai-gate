package com.aigate.router.ui.design

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter

/**
 * QR-код адреса шлюза: подключить ноутбук или другое устройство, не вводя
 * адрес руками. Это особенно к месту, потому что адрес в локальной сети
 * меняется при переключении сетей.
 *
 * Битмап рисуется здесь же — из зависимости берётся только кодер.
 */
@Composable
fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,
) {
    val sizePx = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(content, sizePx) { qrBitmap(content, sizePx) } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR-код адреса шлюза",
        modifier = modifier.size(size),
    )
}

/** Чёрный код на прозрачном фоне; null, если строка не кодируется. */
fun qrBitmap(content: String, sizePx: Int): Bitmap? {
    if (content.isBlank() || sizePx <= 0) return null
    return runCatching {
        val matrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap.setPixel(
                    x, y,
                    if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.TRANSPARENT,
                )
            }
        }
        bitmap
    }.getOrNull()
}
