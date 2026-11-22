package gr.gamemap

import androidx.car.app.model.CarColor
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.TestCarContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Android Auto's ☰ (the one button the car keeps) in the style's colours, in every style (photo, 22 Nov). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CarMenuStyleTest {
    @Test
    fun menuInStyle() {
        val out = File(System.getProperty("previewOut") ?: "build/previews").apply { mkdirs() }
        val ctx = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        androidx.car.app.CarContext::class.java.getDeclaredMethod("updateHandshakeInfo", androidx.car.app.HandshakeInfo::class.java)
            .apply { isAccessible = true }.invoke(ctx, androidx.car.app.HandshakeInfo("com.google.android.projection.gearhead", 7))
        val log = StringBuilder()
        for (th in Theme.entries) {
            MapState.setTheme(th)
            val screen = gr.gamemap.car.MapScreen(ctx)
            val t = screen.onGetTemplate() as NavigationTemplate
            val a = t.actionStrip!!.actions[0]
            val bg = a.backgroundColor; val tint = a.icon?.tint
            log.append("own buttons ${screen.ownButtonsForTest()} err ${screen.menuStyleError} · ")
            log.append("${th.name}: bg ${bg?.type} ${bg?.let { Integer.toHexString(it.color) + "/" + Integer.toHexString(it.colorDark) }} · tint ${tint?.let { Integer.toHexString(it.color) + "/" + Integer.toHexString(it.colorDark) }}\n")
            File(out, "car_menu_style.txt").writeText(log.toString())
            assertTrue("${th.name}: styled $log", tint?.type == CarColor.TYPE_CUSTOM)
        }
        File(out, "car_menu_style.txt").writeText(log.toString())
        // the ☰ opens GameRoute's own menu on the map, not Android Auto's grey list
        val screen = gr.gamemap.car.MapScreen(ctx)
        screen.openMenuForTest()
        val surf = gr.gamemap.car.MapScreen::class.java.getDeclaredField("surface").apply { isAccessible = true }.get(screen)
        val rend = surf.javaClass.getDeclaredField("renderer").apply { isAccessible = true }.get(surf) as MapRenderer
        assertTrue("☰ opens our menu", rend.carPanel?.key == "menu")
        assertTrue("no list screen pushed", ctx.getCarService(androidx.car.app.ScreenManager::class.java).stackSize <= 1)
        screen.openMenuForTest()
        assertTrue("tapped again it closes", rend.carPanel == null)
    }
}
