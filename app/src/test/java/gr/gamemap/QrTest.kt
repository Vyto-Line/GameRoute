package gr.gamemap

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The QR codes the app shows read back (a camera-like decoder), at the sizes they're shown. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class QrTest {
    @Test
    fun readable() {
        for ((text, size) in listOf(
            "https://vyto-line.github.io/GameRoute/convoy/#k7p2qx" to 900,
            "https://vyto-line.github.io/GameRoute/friend/#VYTO-4821" to 384,
            "https://vyto-line.github.io/GameRoute/friend/#ΑΛΕΞ-1234" to 300,
        )) {
            val b = qrBitmap(text, size)
            val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val r = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(b.width, b.height, px))))
            println("QR ${b.width}px: ${r.text}")
            assertEquals(text, r.text)
        }
    }
}
