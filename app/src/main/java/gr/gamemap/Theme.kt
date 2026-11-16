package gr.gamemap

import android.graphics.Color
import android.graphics.Typeface

/** How the HUD panels are drawn. */
enum class HudShape { ROUND, CHAMFER, PIXEL }

/** How live traffic is drawn (glowing lines, arrows along jams), tuned per theme. */
class TrafficLook(
    /** Soft passes under the line: (width in dp, alpha). */
    val glow: List<Pair<Float, Int>> = listOf(12f to 50, 7f to 100),
    /** Main line width in dp. */
    val core: Float = 3.5f,
    /** Outline drawn under the main line, or null. */
    val casing: Int? = null,
    /** Faint line on free-flowing roads: width in dp and alpha (0 = hidden). */
    val freeWidth: Float = 2.2f,
    val freeAlpha: Int = 90,
    /** Colour of the arrows along jams. */
    val arrow: Int = Color.WHITE,
    /** Arrows crawl along the jam in the driving direction. */
    val animate: Boolean = true,
    /** Smooth edges (off for pixel themes). */
    val smooth: Boolean = true,
)

/** Colours for drawing the map itself (from OpenStreetMap vector data). */
class MapStyle(
    val land: Int,
    val water: Int,
    val park: Int,
    val wood: Int,
    val sand: Int,
    val farm: Int,
    val urban: Int,
    val pitch: Int,
    val building: Int,
    /** Building outline, or null for none. */
    val buildingEdge: Int?,
    /** Shoreline line, or null for none. */
    val waterEdge: Int?,
    val motorway: Int,
    val major: Int,
    val minor: Int,
    val path: Int,
    val rail: Int,
    /** Darker outline around roads (old-map double lines), or null. */
    val casing: Int? = null,
    /** Neon-style glow behind roads. */
    val glow: Boolean = false,
    val label: Int,
    val labelHalo: Int,
    val labels: Boolean = true,
    /** Traffic colours: moving freely / slowly / jammed. */
    val trafficFree: Int = Color.rgb(46, 204, 96),
    val trafficSlow: Int = Color.rgb(255, 170, 0),
    val trafficJam: Int = Color.rgb(230, 30, 30),
    /** Lit windows dotted on buildings when zoomed in (night styles), or null. */
    val windows: IntArray? = null,
)

/** Everything that changes between a theme's light and dark version. */
class Look(
    val style: MapStyle,
    val route: Int,
    val routeGlow: Int,
    val player: Int,
    val playerOutline: Int,
    val hudText: Int,
    val hudPanel: Int,
    val traffic: TrafficLook = TrafficLook(),
)

private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)
private fun argb(a: Int, r: Int, g: Int, b: Int) = Color.argb(a, r, g, b)

/**
 * Game-style looks: original designs evoking different game genres. Each one styles the
 * real map data (roads, buildings, water, parks, names) and adds its own HUD and overlays,
 * in a light and a dark version (MapState picks one: automatic by the sun, or fixed).
 */
enum class Theme(
    val title: String,
    private val light: Look,
    private val dark: Look,
    /** Draw the map inside a round radar instead of full screen. */
    val radar: Boolean = false,
    /** Chunky pixels: the map is drawn this many times smaller and scaled up. */
    val pixel: Int = 1,
    val scanlines: Boolean = false,
    val grid: Boolean = false,
    val hud: HudShape = HudShape.ROUND,
    val serif: Boolean = false,
    val dashedRoute: Boolean = false,
    val paper: Boolean = false,
    val sunGlow: Boolean = false,
    /** Faint rain streaks and a haze at the top of the map. */
    val rain: Boolean = false,
    /** A drafting grid over the map (Blueprint). */
    val drafting: Boolean = false,
) {
    OPEN_WORLD(
        "Free Roam",
        light = Look(
            style = MapStyle(
                land = rgb(232, 234, 236), water = rgb(158, 196, 224), park = rgb(200, 226, 196),
                wood = rgb(184, 214, 180), sand = rgb(238, 228, 200), farm = rgb(226, 232, 214),
                urban = rgb(224, 225, 229), pitch = rgb(196, 226, 190),
                building = rgb(212, 214, 218), buildingEdge = rgb(196, 198, 204), waterEdge = null,
                motorway = rgb(255, 214, 140), major = rgb(255, 255, 255), minor = rgb(255, 255, 255),
                path = rgb(214, 214, 218), rail = rgb(170, 170, 178), casing = rgb(186, 190, 198),
                label = rgb(40, 44, 50), labelHalo = rgb(248, 249, 250),
            ),
            route = rgb(150, 60, 230), routeGlow = argb(90, 150, 60, 230),
            player = Color.WHITE, playerOutline = rgb(30, 30, 40),
            hudText = rgb(30, 34, 40), hudPanel = argb(228, 255, 255, 255),
            traffic = TrafficLook(glow = listOf(10f to 45, 6f to 80), freeAlpha = 120),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(36, 41, 46), water = rgb(52, 76, 98), park = rgb(40, 66, 46),
                wood = rgb(34, 58, 40), sand = rgb(108, 100, 76), farm = rgb(48, 56, 44),
                urban = rgb(42, 46, 52), pitch = rgb(46, 74, 50),
                building = rgb(56, 62, 69), buildingEdge = rgb(68, 75, 83), waterEdge = null,
                motorway = rgb(205, 205, 205), major = rgb(160, 165, 170), minor = rgb(98, 104, 110),
                path = rgb(74, 80, 86), rail = rgb(84, 84, 90),
                label = rgb(225, 228, 232), labelHalo = rgb(20, 22, 26),
            ),
            route = rgb(177, 77, 255), routeGlow = argb(110, 177, 77, 255),
            player = Color.WHITE, playerOutline = Color.BLACK,
            hudText = Color.WHITE, hudPanel = argb(170, 0, 0, 0),
        ),
    ),
    NEON_NOIR(
        "Might City",
        light = Look(
            style = MapStyle(
                land = rgb(228, 240, 242), water = rgb(176, 222, 234), park = rgb(206, 234, 226),
                wood = rgb(196, 228, 218), sand = rgb(236, 236, 220), farm = rgb(220, 236, 230),
                urban = rgb(220, 232, 236), pitch = rgb(200, 232, 222),
                building = rgb(212, 228, 232), buildingEdge = rgb(150, 204, 214), waterEdge = rgb(0, 170, 190),
                motorway = rgb(0, 150, 170), major = rgb(0, 184, 204), minor = rgb(130, 208, 218),
                path = rgb(170, 220, 226), rail = rgb(230, 40, 110), glow = true,
                label = rgb(8, 48, 56), labelHalo = rgb(240, 250, 252),
                trafficFree = rgb(0, 170, 110), trafficSlow = rgb(240, 140, 0), trafficJam = rgb(230, 20, 80),
            ),
            route = rgb(220, 160, 0), routeGlow = argb(100, 220, 160, 0),
            player = rgb(230, 40, 80), playerOutline = rgb(20, 40, 48),
            hudText = rgb(8, 60, 70), hudPanel = argb(228, 240, 250, 252),
            traffic = TrafficLook(glow = listOf(12f to 40, 7f to 80), core = 3f, freeWidth = 2f, freeAlpha = 120),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(6, 18, 22), water = rgb(2, 8, 13), park = rgb(8, 32, 32),
                wood = rgb(6, 28, 28), sand = rgb(20, 34, 34), farm = rgb(8, 24, 26),
                urban = rgb(10, 24, 30), pitch = rgb(10, 40, 40),
                building = rgb(11, 34, 41), buildingEdge = rgb(28, 112, 130), waterEdge = rgb(32, 196, 214),
                motorway = rgb(0, 255, 240), major = rgb(32, 196, 214), minor = rgb(16, 96, 112),
                path = rgb(14, 70, 82), rail = rgb(255, 51, 120), glow = true,
                label = rgb(190, 250, 255), labelHalo = rgb(0, 6, 8),
                trafficFree = rgb(0, 230, 140), trafficSlow = rgb(255, 160, 0), trafficJam = rgb(255, 30, 90),
            ),
            route = rgb(252, 238, 10), routeGlow = argb(120, 252, 238, 10),
            player = rgb(255, 51, 85), playerOutline = rgb(252, 238, 10),
            hudText = rgb(252, 238, 10), hudPanel = argb(200, 6, 20, 24),
            traffic = TrafficLook(glow = listOf(16f to 45, 9f to 110), core = 3f, freeWidth = 2f, freeAlpha = 130),
        ),
        scanlines = true, hud = HudShape.CHAMFER,
    ),
    NEON_RACER(
        "Midnight Run",
        light = Look(
            style = MapStyle(
                land = rgb(250, 240, 252), water = rgb(214, 228, 255), park = rgb(240, 230, 250),
                wood = rgb(236, 224, 248), sand = rgb(250, 240, 240), farm = rgb(246, 236, 250),
                urban = rgb(244, 234, 250), pitch = rgb(238, 228, 250),
                building = rgb(240, 226, 248), buildingEdge = rgb(226, 170, 236), waterEdge = rgb(120, 160, 255),
                motorway = rgb(190, 0, 170), major = rgb(236, 60, 200), minor = rgb(244, 170, 226),
                path = rgb(240, 200, 236), rail = rgb(0, 160, 200), glow = true,
                label = rgb(70, 0, 80), labelHalo = rgb(255, 250, 255),
                trafficFree = rgb(0, 190, 150), trafficSlow = rgb(250, 150, 0), trafficJam = rgb(230, 0, 60),
            ),
            route = rgb(0, 170, 220), routeGlow = argb(100, 0, 170, 220),
            player = rgb(230, 40, 200), playerOutline = rgb(60, 0, 70),
            hudText = rgb(110, 0, 120), hudPanel = argb(228, 255, 245, 255),
            traffic = TrafficLook(glow = listOf(12f to 40, 7f to 80), core = 3f, freeWidth = 2f, freeAlpha = 120),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(16, 4, 30), water = rgb(5, 1, 18), park = rgb(24, 6, 42),
                wood = rgb(22, 4, 38), sand = rgb(34, 12, 48), farm = rgb(20, 6, 36),
                urban = rgb(22, 8, 40), pitch = rgb(28, 8, 50),
                building = rgb(32, 10, 54), buildingEdge = rgb(120, 30, 150), waterEdge = rgb(0, 160, 200),
                motorway = rgb(255, 150, 240), major = rgb(255, 43, 214), minor = rgb(140, 30, 160),
                path = rgb(90, 20, 110), rail = rgb(0, 200, 230), glow = true,
                label = rgb(170, 245, 255), labelHalo = rgb(10, 0, 22),
                trafficFree = rgb(0, 255, 170), trafficSlow = rgb(255, 200, 0), trafficJam = rgb(255, 40, 40),
            ),
            route = rgb(0, 240, 255), routeGlow = argb(120, 0, 240, 255),
            player = rgb(255, 43, 214), playerOutline = Color.WHITE,
            hudText = rgb(0, 240, 255), hudPanel = argb(170, 20, 0, 40),
            traffic = TrafficLook(glow = listOf(16f to 45, 9f to 110), core = 3f, freeWidth = 2f, freeAlpha = 130),
        ),
        grid = true,
    ),
    BLOCKY(
        "Blockworld",
        // Blocky draws its own world; these colours are for the background, HUD and traffic.
        light = Look(
            style = blockyStyle(rgb(94, 158, 58), rgb(63, 118, 228)),
            route = rgb(255, 40, 24), routeGlow = argb(130, 120, 0, 0),
            player = Color.WHITE, playerOutline = rgb(30, 30, 30),
            hudText = Color.WHITE, hudPanel = argb(200, 40, 40, 40),
            traffic = TrafficLook(glow = listOf(9f to 70), core = 5f, casing = rgb(25, 25, 25), freeWidth = 0f, freeAlpha = 0, animate = false, smooth = false),
        ),
        dark = Look(
            style = blockyStyle(rgb(26, 44, 30), rgb(22, 38, 84)),
            route = rgb(255, 60, 40), routeGlow = argb(150, 200, 20, 0),
            player = Color.WHITE, playerOutline = rgb(20, 20, 20),
            hudText = Color.WHITE, hudPanel = argb(210, 20, 20, 24),
            traffic = TrafficLook(glow = listOf(9f to 90), core = 5f, casing = rgb(10, 10, 10), freeWidth = 0f, freeAlpha = 0, animate = false, smooth = false),
        ),
        hud = HudShape.PIXEL,
    ),
    PARCHMENT_QUEST(
        "Realm Map",
        light = Look(
            style = MapStyle(
                land = rgb(234, 217, 174), water = rgb(160, 190, 180), park = rgb(212, 210, 158),
                wood = rgb(184, 190, 132), sand = rgb(238, 222, 170), farm = rgb(224, 210, 160),
                urban = rgb(226, 206, 164), pitch = rgb(206, 208, 150),
                building = rgb(214, 188, 140), buildingEdge = rgb(120, 88, 54), waterEdge = rgb(84, 108, 100),
                motorway = rgb(222, 150, 110), major = rgb(246, 234, 200), minor = rgb(246, 236, 206),
                path = rgb(120, 90, 55), rail = rgb(90, 66, 40), casing = rgb(110, 80, 48),
                label = rgb(74, 52, 32), labelHalo = rgb(240, 226, 186),
                trafficFree = rgb(96, 140, 60), trafficSlow = rgb(214, 140, 30), trafficJam = rgb(160, 24, 20),
            ),
            route = rgb(179, 38, 30), routeGlow = argb(70, 179, 38, 30),
            player = rgb(46, 110, 82), playerOutline = rgb(250, 240, 210),
            hudText = rgb(74, 52, 32), hudPanel = argb(225, 240, 226, 186),
            traffic = TrafficLook(glow = emptyList(), core = 3.2f, casing = rgb(74, 52, 32), freeWidth = 0f, freeAlpha = 0, arrow = rgb(74, 52, 32), animate = false),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(42, 35, 28), water = rgb(30, 44, 50), park = rgb(54, 54, 36),
                wood = rgb(48, 52, 34), sand = rgb(64, 56, 40), farm = rgb(52, 46, 34),
                urban = rgb(50, 42, 34), pitch = rgb(52, 54, 36),
                building = rgb(70, 58, 44), buildingEdge = rgb(150, 122, 84), waterEdge = rgb(110, 132, 122),
                motorway = rgb(160, 104, 74), major = rgb(98, 82, 60), minor = rgb(84, 72, 54),
                path = rgb(150, 120, 80), rail = rgb(140, 110, 74), casing = rgb(186, 156, 108),
                label = rgb(232, 214, 176), labelHalo = rgb(36, 30, 24),
                trafficFree = rgb(110, 150, 70), trafficSlow = rgb(226, 156, 54), trafficJam = rgb(226, 64, 44),
            ),
            route = rgb(226, 80, 56), routeGlow = argb(80, 226, 80, 56),
            player = rgb(110, 190, 150), playerOutline = rgb(240, 226, 190),
            hudText = rgb(232, 214, 176), hudPanel = argb(228, 46, 38, 30),
            traffic = TrafficLook(glow = emptyList(), core = 3.2f, casing = rgb(232, 214, 176), freeWidth = 0f, freeAlpha = 0, arrow = rgb(36, 30, 24), animate = false),
        ),
        serif = true, dashedRoute = true, paper = true,
    ),
    LOOKOUT(
        "Fire Tower",
        light = Look(
            style = MapStyle(
                land = rgb(248, 230, 206), water = rgb(150, 198, 222), park = rgb(214, 222, 176),
                wood = rgb(186, 204, 156), sand = rgb(246, 226, 190), farm = rgb(238, 226, 190),
                urban = rgb(240, 222, 200), pitch = rgb(210, 222, 170),
                building = rgb(238, 210, 184), buildingEdge = rgb(214, 176, 146), waterEdge = rgb(100, 150, 180),
                motorway = rgb(214, 96, 44), major = rgb(236, 140, 70), minor = rgb(252, 242, 228),
                path = rgb(220, 190, 160), rail = rgb(180, 140, 120), casing = rgb(214, 180, 150),
                label = rgb(96, 52, 40), labelHalo = rgb(252, 240, 224),
                trafficFree = rgb(90, 170, 110), trafficSlow = rgb(240, 150, 40), trafficJam = rgb(220, 60, 50),
            ),
            route = rgb(30, 120, 150), routeGlow = argb(90, 63, 184, 175),
            player = rgb(30, 140, 140), playerOutline = Color.WHITE,
            hudText = rgb(96, 52, 40), hudPanel = argb(228, 252, 242, 228),
            traffic = TrafficLook(glow = listOf(10f to 45, 6f to 80), core = 3.2f, freeAlpha = 110),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(52, 32, 68), water = rgb(30, 42, 78), park = rgb(44, 28, 60),
                wood = rgb(36, 22, 52), sand = rgb(110, 62, 70), farm = rgb(60, 36, 70),
                urban = rgb(60, 36, 72), pitch = rgb(56, 34, 70),
                building = rgb(84, 46, 88), buildingEdge = rgb(120, 64, 106), waterEdge = rgb(247, 213, 155),
                motorway = rgb(247, 213, 155), major = rgb(232, 131, 58), minor = rgb(168, 78, 74),
                path = rgb(120, 60, 80), rail = rgb(140, 90, 110),
                label = rgb(247, 213, 155), labelHalo = rgb(43, 27, 61),
                trafficFree = rgb(46, 204, 96), trafficSlow = rgb(255, 196, 80), trafficJam = rgb(255, 70, 60),
            ),
            route = rgb(242, 242, 232), routeGlow = argb(110, 63, 184, 175),
            player = rgb(63, 184, 175), playerOutline = rgb(242, 242, 232),
            hudText = rgb(247, 213, 155), hudPanel = argb(190, 43, 27, 61),
            traffic = TrafficLook(glow = listOf(12f to 50, 7f to 90), core = 3.2f, freeAlpha = 80, arrow = rgb(247, 213, 155)),
        ),
        sunGlow = true,
    ),
    RETRO(
        "8-Bit",
        light = Look(
            style = gameBoy(dark = false),
            route = rgb(15, 56, 15), routeGlow = argb(90, 48, 98, 48),
            player = rgb(15, 56, 15), playerOutline = rgb(155, 188, 15),
            hudText = rgb(15, 56, 15), hudPanel = argb(210, 139, 172, 15),
            traffic = TrafficLook(glow = emptyList(), core = 4f, casing = rgb(15, 56, 15), freeWidth = 0f, freeAlpha = 0, arrow = rgb(15, 56, 15), animate = false, smooth = false),
        ),
        dark = Look(
            style = gameBoy(dark = true),
            route = rgb(155, 188, 15), routeGlow = argb(90, 139, 172, 15),
            player = rgb(155, 188, 15), playerOutline = rgb(15, 56, 15),
            hudText = rgb(155, 188, 15), hudPanel = argb(220, 15, 56, 15),
            traffic = TrafficLook(glow = emptyList(), core = 4f, casing = rgb(15, 56, 15), freeWidth = 0f, freeAlpha = 0, arrow = rgb(15, 56, 15), animate = false, smooth = false),
        ),
        pixel = 4, hud = HudShape.PIXEL,
    ),
    TRAILBLAZER(
        "Catch 'Em Trail",
        light = Look(
            style = MapStyle(
                land = rgb(150, 214, 140), water = rgb(84, 176, 240), park = rgb(112, 196, 112),
                wood = rgb(92, 178, 98), sand = rgb(236, 222, 160), farm = rgb(170, 220, 140),
                urban = rgb(160, 206, 150), pitch = rgb(124, 206, 120),
                building = rgb(176, 226, 168), buildingEdge = rgb(132, 196, 128), waterEdge = rgb(200, 236, 255),
                motorway = rgb(255, 236, 150), major = rgb(206, 246, 236), minor = rgb(198, 240, 228),
                path = rgb(226, 246, 214), rail = rgb(130, 170, 130), casing = Color.WHITE,
                label = rgb(28, 84, 64), labelHalo = rgb(236, 252, 236),
                trafficFree = rgb(40, 190, 90), trafficSlow = rgb(255, 160, 40), trafficJam = rgb(240, 60, 60),
            ),
            route = rgb(84, 104, 255), routeGlow = argb(110, 84, 104, 255),
            player = Color.WHITE, playerOutline = rgb(30, 120, 200),
            hudText = rgb(24, 70, 64), hudPanel = argb(235, 250, 255, 250),
            traffic = TrafficLook(glow = listOf(10f to 40), core = 4f, casing = Color.WHITE, freeWidth = 2.5f, freeAlpha = 150),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(28, 58, 68), water = rgb(18, 38, 92), park = rgb(32, 78, 70),
                wood = rgb(26, 68, 60), sand = rgb(70, 76, 70), farm = rgb(36, 66, 66),
                urban = rgb(34, 62, 72), pitch = rgb(34, 84, 72),
                building = rgb(44, 84, 94), buildingEdge = rgb(64, 112, 122), waterEdge = rgb(80, 140, 220),
                motorway = rgb(214, 210, 130), major = rgb(110, 190, 200), minor = rgb(76, 150, 160),
                path = rgb(70, 120, 120), rail = rgb(90, 130, 130), casing = rgb(16, 36, 44),
                label = rgb(210, 240, 236), labelHalo = rgb(16, 36, 44),
                trafficFree = rgb(60, 220, 130), trafficSlow = rgb(255, 176, 60), trafficJam = rgb(255, 80, 80),
            ),
            route = rgb(130, 150, 255), routeGlow = argb(120, 130, 150, 255),
            player = Color.WHITE, playerOutline = rgb(60, 160, 240),
            hudText = rgb(210, 240, 236), hudPanel = argb(228, 20, 44, 52),
            traffic = TrafficLook(glow = listOf(12f to 50), core = 4f, casing = rgb(16, 36, 44), freeWidth = 2.5f, freeAlpha = 130),
        ),
    ),
    VIOLET_RAIN(
        "Violet Rain",
        light = Look(
            style = MapStyle(
                land = rgb(234, 228, 244), water = rgb(196, 186, 234), park = rgb(218, 226, 220),
                wood = rgb(206, 218, 210), sand = rgb(238, 230, 226), farm = rgb(230, 228, 236),
                urban = rgb(226, 218, 238), pitch = rgb(214, 226, 218),
                building = rgb(220, 210, 238), buildingEdge = rgb(190, 172, 222), waterEdge = rgb(140, 110, 220),
                motorway = rgb(140, 70, 214), major = rgb(176, 120, 240), minor = Color.WHITE,
                path = rgb(210, 196, 232), rail = rgb(214, 60, 170), casing = rgb(192, 176, 224),
                label = rgb(52, 22, 92), labelHalo = rgb(246, 242, 252),
                trafficFree = rgb(30, 170, 120), trafficSlow = rgb(240, 140, 0), trafficJam = rgb(220, 30, 90),
            ),
            route = rgb(232, 124, 16), routeGlow = argb(100, 232, 124, 16),
            player = rgb(222, 40, 160), playerOutline = rgb(52, 22, 92),
            hudText = rgb(52, 22, 92), hudPanel = argb(232, 250, 246, 255),
            traffic = TrafficLook(glow = listOf(10f to 40, 6f to 80), core = 3.5f, freeWidth = 2f, freeAlpha = 120),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(19, 10, 36), water = rgb(27, 17, 69), park = rgb(24, 22, 46),
                wood = rgb(22, 20, 42), sand = rgb(40, 30, 56), farm = rgb(22, 14, 40),
                urban = rgb(24, 14, 44), pitch = rgb(28, 22, 54),
                building = rgb(32, 18, 56), buildingEdge = rgb(62, 36, 104), waterEdge = rgb(96, 64, 210),
                motorway = rgb(233, 168, 255), major = rgb(163, 91, 255), minor = rgb(78, 48, 134),
                path = rgb(60, 40, 100), rail = rgb(255, 79, 216), glow = true,
                label = rgb(238, 221, 255), labelHalo = rgb(11, 6, 22),
                trafficFree = rgb(60, 220, 160), trafficSlow = rgb(255, 170, 60), trafficJam = rgb(255, 60, 110),
                windows = intArrayOf(argb(220, 255, 184, 105), argb(200, 197, 139, 255), argb(190, 255, 111, 224), argb(170, 143, 227, 255)),
            ),
            route = rgb(255, 160, 64), routeGlow = argb(130, 255, 160, 64),
            player = rgb(255, 79, 216), playerOutline = Color.WHITE,
            hudText = rgb(238, 221, 255), hudPanel = argb(215, 18, 9, 36),
            traffic = TrafficLook(glow = listOf(16f to 45, 9f to 110), core = 3f, freeWidth = 2f, freeAlpha = 130),
        ),
        hud = HudShape.CHAMFER, rain = true,
    ),
    RALLY(
        "Rally Roadbook",
        light = Look(
            style = MapStyle(
                land = rgb(233, 214, 168), water = rgb(150, 190, 200), park = rgb(214, 206, 150),
                wood = rgb(200, 196, 140), sand = rgb(240, 224, 180), farm = rgb(226, 210, 160),
                urban = rgb(226, 206, 160), pitch = rgb(210, 204, 150),
                building = rgb(220, 198, 148), buildingEdge = rgb(168, 138, 85), waterEdge = rgb(110, 150, 165),
                motorway = rgb(242, 165, 65), major = rgb(255, 247, 226), minor = rgb(246, 233, 200),
                path = rgb(200, 176, 130), rail = rgb(150, 120, 80), casing = rgb(168, 138, 85),
                label = rgb(90, 62, 30), labelHalo = rgb(246, 236, 210),
            ),
            route = rgb(200, 50, 29), routeGlow = argb(90, 200, 50, 29),
            player = rgb(200, 50, 29), playerOutline = rgb(42, 20, 8),
            hudText = rgb(30, 20, 10), hudPanel = argb(242, 251, 243, 220),
            traffic = TrafficLook(glow = listOf(8f to 40), core = 4f, casing = rgb(42, 20, 8), freeWidth = 2f, freeAlpha = 110),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(52, 42, 30), water = rgb(30, 44, 54), park = rgb(58, 52, 34),
                wood = rgb(50, 46, 30), sand = rgb(70, 58, 40), farm = rgb(56, 46, 32),
                urban = rgb(60, 48, 34), pitch = rgb(60, 56, 36),
                building = rgb(72, 58, 40), buildingEdge = rgb(96, 78, 52), waterEdge = rgb(60, 90, 100),
                motorway = rgb(240, 160, 60), major = rgb(214, 190, 140), minor = rgb(130, 110, 80),
                path = rgb(100, 86, 62), rail = rgb(110, 90, 64), casing = rgb(30, 24, 16),
                label = rgb(240, 222, 180), labelHalo = rgb(30, 24, 16),
            ),
            route = rgb(255, 96, 60), routeGlow = argb(110, 255, 96, 60),
            player = rgb(255, 96, 60), playerOutline = rgb(250, 240, 220),
            hudText = rgb(250, 236, 206), hudPanel = argb(232, 40, 32, 22),
        ),
    ),
    BLUEPRINT(
        "Blueprint",
        light = Look(
            style = MapStyle(
                land = rgb(240, 246, 252), water = rgb(200, 222, 245), park = rgb(230, 240, 250),
                wood = rgb(226, 238, 250), sand = rgb(240, 246, 252), farm = rgb(236, 244, 252),
                urban = rgb(236, 243, 251), pitch = rgb(230, 240, 250),
                building = rgb(240, 246, 252), buildingEdge = rgb(70, 120, 196), waterEdge = rgb(60, 110, 190),
                motorway = rgb(240, 246, 252), major = rgb(240, 246, 252), minor = rgb(240, 246, 252),
                path = rgb(120, 160, 214), rail = rgb(100, 140, 200), casing = rgb(30, 80, 166),
                label = rgb(20, 60, 130), labelHalo = rgb(240, 246, 252),
            ),
            route = rgb(245, 130, 0), routeGlow = argb(110, 245, 130, 0),
            player = rgb(20, 60, 130), playerOutline = Color.WHITE,
            hudText = rgb(20, 60, 130), hudPanel = argb(242, 250, 252, 255),
            traffic = TrafficLook(glow = listOf(8f to 40), core = 3f, freeWidth = 2f, freeAlpha = 110),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(15, 60, 120), water = rgb(10, 40, 92), park = rgb(18, 66, 126),
                wood = rgb(18, 66, 126), sand = rgb(22, 70, 128), farm = rgb(16, 62, 122),
                urban = rgb(17, 63, 124), pitch = rgb(20, 70, 130),
                building = rgb(15, 60, 120), buildingEdge = rgb(140, 182, 236), waterEdge = rgb(207, 227, 255),
                motorway = rgb(15, 60, 120), major = rgb(15, 60, 120), minor = rgb(15, 60, 120),
                path = rgb(90, 140, 200), rail = rgb(120, 160, 210), casing = rgb(207, 227, 255),
                label = rgb(214, 232, 255), labelHalo = rgb(15, 60, 120),
            ),
            route = rgb(255, 227, 77), routeGlow = argb(120, 255, 227, 77),
            player = Color.WHITE, playerOutline = rgb(10, 36, 84),
            hudText = Color.WHITE, hudPanel = argb(236, 15, 60, 120),
        ),
        hud = HudShape.CHAMFER, drafting = true,
    ),
    NAUTICAL(
        "Nautical Chart",
        light = Look(
            style = MapStyle(
                land = rgb(241, 228, 195), water = rgb(159, 203, 224), park = rgb(226, 222, 180),
                wood = rgb(214, 214, 170), sand = rgb(246, 236, 205), farm = rgb(236, 226, 190),
                urban = rgb(234, 220, 186), pitch = rgb(226, 222, 180),
                building = rgb(230, 214, 176), buildingEdge = rgb(180, 150, 100), waterEdge = rgb(90, 140, 170),
                motorway = rgb(160, 118, 66), major = rgb(190, 156, 108), minor = rgb(214, 190, 148),
                path = rgb(200, 180, 140), rail = rgb(140, 110, 70),
                label = rgb(74, 52, 24), labelHalo = rgb(246, 236, 210),
            ),
            route = rgb(176, 34, 46), routeGlow = argb(80, 176, 34, 46),
            player = rgb(176, 34, 46), playerOutline = rgb(28, 42, 58),
            hudText = rgb(42, 30, 12), hudPanel = argb(242, 248, 239, 214),
            traffic = TrafficLook(glow = listOf(8f to 40), core = 3.5f, freeWidth = 2f, freeAlpha = 110),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(34, 44, 58), water = rgb(16, 26, 40), park = rgb(40, 52, 60),
                wood = rgb(38, 50, 58), sand = rgb(52, 58, 64), farm = rgb(36, 46, 58),
                urban = rgb(38, 48, 62), pitch = rgb(40, 52, 60),
                building = rgb(46, 58, 72), buildingEdge = rgb(80, 96, 112), waterEdge = rgb(90, 140, 170),
                motorway = rgb(210, 180, 120), major = rgb(170, 150, 110), minor = rgb(110, 104, 90),
                path = rgb(90, 86, 76), rail = rgb(100, 96, 86),
                label = rgb(230, 214, 180), labelHalo = rgb(16, 26, 40),
            ),
            route = rgb(255, 92, 92), routeGlow = argb(110, 255, 92, 92),
            player = rgb(255, 92, 92), playerOutline = rgb(240, 230, 210),
            hudText = rgb(240, 226, 196), hudPanel = argb(232, 24, 34, 48),
        ),
        serif = true, paper = true,
    ),
    ARCTIC(
        "Arctic",
        light = Look(
            style = MapStyle(
                land = rgb(234, 242, 248), water = rgb(176, 206, 228), park = rgb(222, 236, 240),
                wood = rgb(214, 230, 236), sand = rgb(240, 244, 246), farm = rgb(230, 238, 244),
                urban = rgb(226, 234, 242), pitch = rgb(222, 236, 240),
                building = Color.WHITE, buildingEdge = rgb(190, 210, 226), waterEdge = rgb(140, 180, 210),
                motorway = rgb(141, 179, 211), major = rgb(180, 205, 226), minor = rgb(211, 227, 239),
                path = rgb(200, 214, 226), rail = rgb(160, 180, 200),
                label = rgb(27, 58, 85), labelHalo = Color.WHITE,
            ),
            route = rgb(215, 38, 61), routeGlow = argb(90, 215, 38, 61),
            player = rgb(215, 38, 61), playerOutline = Color.WHITE,
            hudText = rgb(27, 58, 85), hudPanel = argb(244, 255, 255, 255),
            traffic = TrafficLook(glow = listOf(10f to 40), core = 3.5f, freeWidth = 2f, freeAlpha = 120),
        ),
        dark = Look(
            style = MapStyle(
                land = rgb(16, 28, 44), water = rgb(8, 18, 32), park = rgb(18, 34, 48),
                wood = rgb(18, 34, 48), sand = rgb(28, 40, 54), farm = rgb(18, 30, 46),
                urban = rgb(20, 32, 48), pitch = rgb(20, 36, 50),
                building = rgb(26, 42, 60), buildingEdge = rgb(44, 66, 92), waterEdge = rgb(120, 170, 220),
                motorway = rgb(190, 220, 245), major = rgb(130, 170, 210), minor = rgb(60, 90, 124),
                path = rgb(50, 76, 104), rail = rgb(60, 80, 100),
                label = rgb(220, 236, 250), labelHalo = rgb(8, 18, 32),
            ),
            route = rgb(255, 80, 100), routeGlow = argb(120, 255, 80, 100),
            player = rgb(255, 80, 100), playerOutline = Color.WHITE,
            hudText = rgb(225, 238, 250), hudPanel = argb(228, 14, 26, 42),
        ),
    ),
    HIGH_CONTRAST(
        "High Contrast",
        light = Look(
            style = MapStyle(
                land = Color.WHITE, water = rgb(150, 196, 255), park = rgb(214, 238, 214),
                wood = rgb(200, 230, 200), sand = rgb(246, 240, 220), farm = rgb(240, 240, 232),
                urban = rgb(236, 236, 236), pitch = rgb(214, 238, 214),
                building = rgb(206, 206, 206), buildingEdge = null, waterEdge = rgb(0, 80, 200),
                motorway = Color.BLACK, major = Color.BLACK, minor = rgb(110, 110, 110),
                path = rgb(150, 150, 150), rail = rgb(120, 120, 120),
                label = Color.BLACK, labelHalo = Color.WHITE,
                trafficFree = rgb(0, 150, 60), trafficSlow = rgb(220, 120, 0), trafficJam = rgb(210, 0, 0),
            ),
            route = rgb(0, 70, 230), routeGlow = argb(110, 0, 70, 230),
            player = rgb(230, 0, 120), playerOutline = Color.BLACK,
            hudText = Color.BLACK, hudPanel = argb(252, 255, 255, 255),
            traffic = TrafficLook(glow = emptyList(), core = 5f, casing = Color.WHITE, freeWidth = 0f, freeAlpha = 0),
        ),
        dark = Look(
            style = MapStyle(
                land = Color.BLACK, water = rgb(0, 30, 64), park = rgb(10, 32, 10),
                wood = rgb(10, 32, 10), sand = rgb(24, 24, 24), farm = rgb(12, 12, 12),
                urban = rgb(14, 14, 14), pitch = rgb(10, 32, 10),
                building = rgb(36, 36, 36), buildingEdge = null, waterEdge = rgb(0, 140, 255),
                motorway = Color.WHITE, major = Color.WHITE, minor = rgb(130, 130, 130),
                path = rgb(90, 90, 90), rail = rgb(110, 110, 110),
                label = Color.WHITE, labelHalo = Color.BLACK,
                trafficFree = rgb(0, 230, 100), trafficSlow = rgb(255, 170, 0), trafficJam = rgb(255, 50, 50),
            ),
            route = rgb(255, 212, 0), routeGlow = argb(140, 255, 212, 0),
            player = rgb(0, 229, 255), playerOutline = Color.BLACK,
            hudText = Color.WHITE, hudPanel = argb(252, 0, 0, 0),
            traffic = TrafficLook(glow = emptyList(), core = 5f, casing = Color.BLACK, freeWidth = 0f, freeAlpha = 0),
        ),
    );

    /** The light or dark version, as chosen in MapState. */
    val look: Look get() = lookFor(MapState.dark)

    fun lookFor(isDark: Boolean): Look = if (isDark) dark else light

    val style: MapStyle get() = look.style
    val route: Int get() = look.route
    val routeGlow: Int get() = look.routeGlow
    val player: Int get() = look.player
    val playerOutline: Int get() = look.playerOutline
    val hudText: Int get() = look.hudText
    val hudPanel: Int get() = look.hudPanel
    val traffic: TrafficLook get() = look.traffic

    /** Colour behind the map while tiles load. */
    val background: Int get() = style.land

    val typeface: Typeface
        get() = if (serif) Typeface.create(Typeface.SERIF, Typeface.BOLD)
        else Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
}

private fun blockyStyle(land: Int, water: Int) = MapStyle(
    land = land, water = water, park = 0, wood = 0, sand = 0, farm = 0,
    urban = 0, pitch = 0, building = 0, buildingEdge = null, waterEdge = null,
    motorway = 0, major = 0, minor = 0, path = 0, rail = 0, label = 0, labelHalo = 0, labels = false,
    trafficFree = rgb(80, 220, 60), trafficSlow = rgb(255, 200, 40), trafficJam = rgb(220, 20, 10),
)

/** Handheld-console palette: four greens, inverted for dark mode. */
private fun gameBoy(dark: Boolean): MapStyle {
    val l0 = rgb(155, 188, 15); val l1 = rgb(139, 172, 15); val l2 = rgb(48, 98, 48); val l3 = rgb(15, 56, 15)
    return if (!dark) MapStyle(
        land = l0, water = l2, park = l1, wood = l1, sand = l0, farm = l0, urban = l1, pitch = l1,
        building = l1, buildingEdge = null, waterEdge = null,
        motorway = l3, major = l3, minor = l2, path = l2, rail = l2,
        label = 0, labelHalo = 0, labels = false,
        trafficFree = l1, trafficSlow = l1, trafficJam = l0,
    ) else MapStyle(
        land = l3, water = l2, park = l2, wood = l2, sand = l3, farm = l3, urban = l2, pitch = l2,
        building = l2, buildingEdge = null, waterEdge = null,
        motorway = l0, major = l0, minor = l1, path = l1, rail = l1,
        label = 0, labelHalo = 0, labels = false,
        trafficFree = l2, trafficSlow = l1, trafficJam = l0,
    )
}
