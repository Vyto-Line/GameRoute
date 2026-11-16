package gr.gamemap

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A QR code bitmap about [size] px wide: black on white, with the 4-module quiet zone scanners
 * need, every module the same whole number of pixels (no blurred edges from scaling).
 */
fun qrBitmap(text: String, size: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints) // one pixel per module
    val quiet = 4
    val modules = m.width + quiet * 2
    val k = maxOf(1, size / modules)
    val side = modules * k
    val px = IntArray(side * side) { Color.WHITE }
    for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) {
        val x0 = (x + quiet) * k; val y0 = (y + quiet) * k
        for (dy in 0 until k) { val row = (y0 + dy) * side; for (dx in 0 until k) px[row + x0 + dx] = Color.BLACK }
    }
    return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
}
