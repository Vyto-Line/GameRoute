package gr.gamemap.car

import android.Manifest
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import gr.gamemap.LocationTracker
import gr.gamemap.MapRenderer
import gr.gamemap.MapState
import gr.gamemap.NavEngine
import gr.gamemap.NavStep
import gr.gamemap.Place
import gr.gamemap.Places
import gr.gamemap.R
import gr.gamemap.TileCache
import gr.gamemap.Voice
import java.util.TimeZone
import java.util.concurrent.Executors

class GameMapCarAppService : CarAppService() {
    // Sideloaded app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = GameMapSession()
}

class GameMapSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        gr.gamemap.CrashLog.note("Android Auto opened")
        gr.gamemap.Parked.clear() // driving off: the old parking spot is history
        // the car's night mode follows its headlights (or light sensor): dark map at night
        MapState.setCarNight(carContext.isDarkMode)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                MapState.setCarNight(null)
                gr.gamemap.Parked.carDisconnected() // the car is parked where the phone is now
            }
        })
        return MapScreen(carContext)
    }

    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        MapState.setCarNight(carContext.isDarkMode)
    }
}

fun icon(ctx: CarContext, res: Int) = CarIcon.Builder(IconCompat.createWithResource(ctx, res)).build()

/** Draws the game map onto the car's screen surface. */
class CarMapSurface(private val ctx: CarContext) : SurfaceCallback {
    private val renderer = MapRenderer(car = true).apply {
        // our own see-through buttons need the car to report taps (Car App API 5+)
        carButtons = ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_5
    }
    /** Called with the id of a GameMap button tapped on the car screen. */
    var onButton: ((String) -> Unit)? = null
    val ownButtons get() = renderer.carButtons
    private val main = Handler(Looper.getMainLooper())
    /**
     * Drawing has its own thread: the main thread stays free for the car's taps and drags, so
     * they're handled at once instead of waiting behind a frame.
     */
    private val thread = android.os.HandlerThread("car-render", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val rh = Handler(thread.looper)
    private val lock = Any()
    @Volatile private var surface: Surface? = null
    @Volatile private var w = 0
    @Volatile private var h = 0
    @Volatile private var density = 1f
    private val visible = Rect()
    private val draw = Runnable { render() }
    val redraw: () -> Unit = { rh.removeCallbacks(draw); rh.post(draw) }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        synchronized(lock) {
            surface = container.surface
            w = container.width; h = container.height
            density = (container.dpi / 160f).coerceAtLeast(0.75f)
            visible.set(0, 0, w, h)
        }
        redraw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        synchronized(lock) { visible.set(visibleArea) }
        redraw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        renderer.stableTop = stableArea.top
        redraw()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        // waits for a frame being drawn, so the surface is never used after the car takes it back
        synchronized(lock) { surface = null }
    }

    /** The car screen went away for good: stop the drawing thread. */
    fun release() {
        rh.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        MapState.car.zoomByFactor(scaleFactor)
        redraw()
    }

    /** Pan mode (the ✥ button) or a drag on the map: move the map with the finger. */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        val cam = renderer.camera ?: return
        MapState.car.pan(renderer.toMap(cam.cx + distanceX, cam.cy + distanceY, cam), cam.bearing)
        redraw()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {}

    /** The tapped place whose card is open on the car screen. */
    var selectedPlace: Place?
        get() = renderer.selectedPlace
        set(v) { renderer.selectedPlace = v; redraw() }

    override fun onClick(x: Float, y: Float) {
        renderer.carButtonAt(x, y)?.let { onButton?.invoke(it); return }
        // a place icon: open its card (fuel prices filled in when they arrive)
        renderer.placeAt(x, y)?.let { poi ->
            val pl = poi.place()
            selectedPlace = pl
            if (pl.icon == "⛽") Executors.newSingleThreadExecutor().execute {
                val f = try { gr.gamemap.Fuel.forPlace(pl) } catch (e: Exception) { null } ?: return@execute
                main.post { if (selectedPlace?.pos == pl.pos) selectedPlace = pl.copy(extra = f) }
            }
            return
        }
        if (selectedPlace != null) selectedPlace = null
    }

    private var hardware = true

    private fun render() {
        synchronized(lock) {
            val s = surface ?: return
            if (!s.isValid || w == 0) return
            val c = try {
                if (hardware) s.lockHardwareCanvas() else s.lockCanvas(null)
            } catch (e: Exception) {
                if (hardware) { hardware = false; redraw() }
                return
            }
            try {
                renderer.draw(c, w, h, if (visible.isEmpty) Rect(0, 0, w, h) else Rect(visible), density, hud = true)
            } catch (e: Exception) {
                // a frame drawn while the data changed under it: skip it, the next one is fine
            } finally {
                try { s.unlockCanvasAndPost(c) } catch (_: Exception) {}
            }
        }
        // Keep animating (gliding arrow, smooth zoom) at ~30 fps while something moves.
        if (renderer.animating) {
            rh.removeCallbacks(draw)
            rh.postDelayed(draw, if (LocationTracker.speedMps > 8f) 33L else 50L) // 30 fps moving, 20 slow
        }
    }
}

class MapScreen(carContext: CarContext) : Screen(carContext) {
    private val surface = CarMapSurface(carContext)
    private val nav = carContext.getCarService(NavigationManager::class.java)
    private var navigating = false
    private var lastInvalidate = 0L
    private val main = Handler(Looper.getMainLooper())
    private val refresh = Runnable { lastInvalidate = System.currentTimeMillis(); invalidate() }

    /** Template updates are throttled to once a second. */
    private var askedFor: gr.gamemap.Alerts.Report? = null

    /** "Police reported here: still there?" as a car alert. */
    private val onProposal: () -> Unit = {
        gr.gamemap.Convoy.proposal?.let { p ->
            CarToast.makeText(carContext, gr.gamemap.L.t(if (p.place == null) "${p.from} removed the destination" else "${p.from} chose: ${p.place.name}"), CarToast.LENGTH_LONG).show()
        }
    }

    private val onAlerts: () -> Unit = {
        surface.redraw()
        val r = gr.gamemap.Alerts.askAbout
        if (r != null && r !== askedFor && carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
            askedFor = r
            val t = gr.gamemap.Alerts.typeOf(r.type)
            val alert = Alert.Builder(ASK_ALERT, CarText.create("${t.title} reported here"), 10_000L)
                .setSubtitle(CarText.create(gr.gamemap.L.t("Is it still there?")))
                .addAction(Action.Builder().setTitle(gr.gamemap.L.t("Still there")).setOnClickListener { gr.gamemap.Alerts.vote(r, true); gr.gamemap.Alerts.clearAsk() }.build())
                .addAction(Action.Builder().setTitle(gr.gamemap.L.t("Not there")).setOnClickListener { gr.gamemap.Alerts.vote(r, false); gr.gamemap.Alerts.clearAsk() }.build())
                .build()
            try { carContext.getCarService(AppManager::class.java).showAlert(alert) } catch (_: Exception) {}
        }
    }

    private var lastFollowing = true
    private val onMapState: () -> Unit = {
        val f = MapState.car.following && MapState.car.userBearing == null
        if (f != lastFollowing) { lastFollowing = f; onNav() }
    }

    private var previewShown = false
    private var alertFor: NavEngine.Offer? = null

    private val onNav: () -> Unit = {
        syncNavigationState()
        // a route preview started from the phone or the car search shows here too
        if (NavEngine.planning && !previewShown) {
            previewShown = true
            screenManager.push(RoutePreviewScreen(carContext))
        } else if (!NavEngine.planning) previewShown = false
        syncOfferAlert()
        main.removeCallbacks(refresh)
        val wait = 1000 - (System.currentTimeMillis() - lastInvalidate)
        if (wait <= 0) refresh.run() else main.postDelayed(refresh, wait)
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
        surface.onButton = { onCarButton(it) }
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = NavEngine.stop()
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                TileCache.loaded += surface.redraw
                LocationTracker.changed += surface.redraw
                MapState.changed += surface.redraw
                MapState.changed += onMapState
                NavEngine.changed += surface.redraw
                gr.gamemap.Convoy.changed += surface.redraw
                gr.gamemap.Convoy.proposed += onProposal
                NavEngine.changed += onNav
                Voice.changed += onNav
                gr.gamemap.Alerts.changed += onAlerts
                ensureLocation()
                if (gr.gamemap.Updater.dueForCheck(carContext)) Executors.newSingleThreadExecutor().execute {
                    val r = try { gr.gamemap.Updater.check(carContext) } catch (e: Exception) { null }
                    if (r != null) main.post {
                        CarToast.makeText(carContext, gr.gamemap.L.t("GameRoute update ready: open the app on your phone"), CarToast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                surface.release()
                gr.gamemap.Convoy.changed -= surface.redraw
                gr.gamemap.Convoy.proposed -= onProposal
                TileCache.loaded -= surface.redraw
                LocationTracker.changed -= surface.redraw
                MapState.changed -= surface.redraw
                MapState.changed -= onMapState
                NavEngine.changed -= surface.redraw
                NavEngine.changed -= onNav
                Voice.changed -= onNav
                gr.gamemap.Alerts.changed -= onAlerts
                if (navigating) nav.navigationEnded()
                LocationTracker.release(carContext, "car")
            }
        })
    }

    private fun ensureLocation() {
        if (LocationTracker.hasPermission(carContext)) {
            LocationTracker.start(carContext, "car")
        } else {
            carContext.requestPermissions(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            ) { granted, _ ->
                if (granted.isNotEmpty()) LocationTracker.start(carContext, "car")
            }
        }
    }

    private fun syncNavigationState() {
        if (NavEngine.active && !navigating) {
            navigating = true
            try { nav.navigationStarted() } catch (_: Exception) {}
        } else if (!NavEngine.active && navigating) {
            navigating = false
            try { nav.navigationEnded() } catch (_: Exception) {}
        }
    }

    /** "Faster route found" as an Android Auto alert with Switch / Keep (needs a newer car host). */
    private fun syncOfferAlert() {
        val o = NavEngine.offer
        val am = carContext.getCarService(AppManager::class.java)
        if (o == null) {
            if (alertFor != null) {
                alertFor = null
                if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) try { am.dismissAlert(OFFER_ALERT) } catch (_: Exception) {}
            }
            return
        }
        if (alertFor === o) return
        alertFor = o
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_5) {
            CarToast.makeText(carContext, "Faster route: saves ${(o.savingSeconds + 30) / 60} min" +
                if (NavEngine.autoSwitch) ", switching" else "", CarToast.LENGTH_LONG).show()
            return
        }
        val via = o.option.via.takeIf { it.isNotBlank() }?.let { "Via $it" } ?: "Avoids traffic on your route"
        val alert = Alert.Builder(OFFER_ALERT, CarText.create("Faster route · saves ${(o.savingSeconds + 30) / 60} min"), NavEngine.OFFER_SECONDS * 1000L)
            .setSubtitle(CarText.create(via))
            .setIcon(icon(carContext, R.drawable.ic_bolt))
            .addAction(Action.Builder().setTitle(gr.gamemap.L.t("Switch")).setOnClickListener { NavEngine.acceptOffer() }.build())
            .addAction(Action.Builder().setTitle(gr.gamemap.L.t("Keep")).setOnClickListener { NavEngine.declineOffer() }.build())
            .setCallback(object : AlertCallback {
                override fun onCancel(reason: Int) {}
                override fun onDismiss() {}
            })
            .build()
        try { am.showAlert(alert) } catch (_: Exception) {}
    }

    private fun action(res: Int, onClick: () -> Unit) =
        Action.Builder().setIcon(icon(carContext, res)).setOnClickListener(onClick).build()

    /** Parking on the arrival card: a list drawn on the map, the nearest car parks first. */
    private fun openParking() {
        val near = LocationTracker.position ?: return
        val p = gr.gamemap.CarParking
        p.loading = true
        p.list = try { Places.nearbyLocal(listOf("parking"), near) } catch (e: Exception) { emptyList() } // instant
        surface.redraw()
        Executors.newSingleThreadExecutor().execute {
            val more = try { Places.nearbyFull(listOf("parking"), near, p.list.orEmpty()) } catch (e: Exception) { null }
            Handler(Looper.getMainLooper()).post {
                p.loading = false
                if (p.list != null && more != null) p.list = more
                surface.redraw()
            }
        }
    }

    /** A GameMap button drawn on the car map was tapped. */
    private fun onCarButton(id: String) {
        if (id.startsWith("park:")) {
            val pick = gr.gamemap.CarParking.list?.getOrNull(id.removePrefix("park:").toIntOrNull() ?: -1) ?: return
            gr.gamemap.CarParking.list = null
            NavEngine.stop(); NavEngine.dismissTrip()
            NavEngine.start(carContext, pick) // straight there: it's around the corner
            surface.redraw()
            return
        }
        if (id == "convoy:accept") { gr.gamemap.Convoy.accept(); return }
        if (id == "convoy:decline") { gr.gamemap.Convoy.decline(); return }
        if (id.startsWith("friend:")) {
            // a friend in the convoy box: the map goes to them (me: back to my car)
            val fid = id.removePrefix("friend:")
            val m = gr.gamemap.Convoy.members[fid]
            val at = m?.shown ?: m?.pos
            if (fid == gr.gamemap.Convoy.myId || at == null) MapState.car.recenter() else MapState.car.pan(at, 0f)
            return
        }
        when (id) {
            "style" -> CarToast.makeText(carContext, MapState.nextCarTheme().title, CarToast.LENGTH_SHORT).show()
            "traffic" -> {
                gr.gamemap.Traffic.showLayer = !gr.gamemap.Traffic.showLayer
                CarToast.makeText(carContext, if (gr.gamemap.Traffic.showLayer) gr.gamemap.L.t("Traffic shown") else gr.gamemap.L.t("Traffic hidden"), CarToast.LENGTH_SHORT).show()
            }
            "mute", "unmute" -> Voice.muted = !Voice.muted
            "report" -> screenManager.push(ReportScreen(carContext))
            "zoomin" -> MapState.car.zoomBy(1.0)
            "zoomout" -> MapState.car.zoomBy(-1.0)
            "recenter" -> MapState.car.recenter()
            "overview" -> NavEngine.route.takeIf { it.size > 1 }?.let { r -> MapState.car.showOverview(r.filterIndexed { i, _ -> i % 5 == 0 } + r.last()) }
            "end" -> NavEngine.stop()
            "menu" -> screenManager.push(CarMenuScreen(carContext))
            "search" -> screenManager.push(SearchScreen(carContext))
            "home", "work" -> Places.saved(id)?.let { NavEngine.plan(carContext, it) }
            in (0 until 20).map { "fav:$it" } -> Places.labelled().getOrNull(id.removePrefix("fav:").toInt())?.let { NavEngine.plan(carContext, it.place()) }
            "done" -> { NavEngine.stop(); NavEngine.dismissTrip() }
            "parking" -> openParking()
            "parkclose" -> gr.gamemap.CarParking.list = null
            "placeclose" -> surface.selectedPlace = null
            "placestop" -> surface.selectedPlace?.let { pl ->
                surface.selectedPlace = null
                NavEngine.addStop(pl)
            }
            "placego" -> surface.selectedPlace?.let { pl ->
                surface.selectedPlace = null
                NavEngine.start(carContext, pl)
            }
        }
        surface.redraw()
    }

    override fun onGetTemplate(): Template {
        if (surface.ownButtons) {
            // GameMap draws its own see-through buttons; Android Auto keeps one: the ☰ menu
            val b = NavigationTemplate.Builder().setActionStrip(
                ActionStrip.Builder().addAction(action(R.drawable.ic_menu) { screenManager.push(CarMenuScreen(carContext)) }).build()
            )
            b.setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            return b.build()
        }
        val strip = ActionStrip.Builder()
        if (NavEngine.active) {
            strip.addAction(action(if (Voice.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_on) { Voice.muted = !Voice.muted })
        } else {
            strip.addAction(action(R.drawable.ic_search) { screenManager.push(SearchScreen(carContext)) })
        }
        strip.addAction(action(R.drawable.ic_theme) {
            val t = MapState.nextCarTheme()
            CarToast.makeText(carContext, t.title, CarToast.LENGTH_SHORT).show()
        })
        strip.addAction(action(R.drawable.ic_report) { screenManager.push(ReportScreen(carContext)) })
        if (NavEngine.active) strip.addAction(action(R.drawable.ic_stop) { NavEngine.stop() })

        // The turn card, arrival time and speed are drawn on the map itself (see-through,
        // in the map's style), so the map gets the whole screen.
        val b = NavigationTemplate.Builder().setActionStrip(strip.build())
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            val m = ActionStrip.Builder()
                .addAction(Action.PAN)
                .addAction(action(R.drawable.ic_zoom_in) { MapState.car.zoomBy(1.0) })
                .addAction(action(R.drawable.ic_zoom_out) { MapState.car.zoomBy(-1.0) })
            if (!MapState.car.following || MapState.car.userBearing != null) {
                m.addAction(action(R.drawable.ic_recenter) { MapState.car.recenter() })
            } else if (NavEngine.active && NavEngine.route.size > 1) {
                m.addAction(action(R.drawable.ic_overview) {
                    val r = NavEngine.route
                    MapState.car.showOverview(r.filterIndexed { i, _ -> i % 5 == 0 } + r.last())
                })
            }
            b.setMapActionStrip(m.build())
        }
        return b.build()
    }

    private fun distance(m: Double): Distance = when {
        m >= 10_000 -> Distance.create((m / 1000).toInt().toDouble(), Distance.UNIT_KILOMETERS)
        m >= 1000 -> Distance.create(Math.round(m / 100) / 10.0, Distance.UNIT_KILOMETERS_P1)
        m >= 100 -> Distance.create(((m / 50).toInt() * 50).toDouble(), Distance.UNIT_METERS)
        else -> Distance.create(((m / 10).toInt() * 10).toDouble(), Distance.UNIT_METERS)
    }

    private var lanesFor: NavStep? = null
    private var lanesIcon: CarIcon? = null

    private fun toCarStep(s: NavStep, withLanes: Boolean): Step {
        val roundabout = (s.type == "roundabout" || s.type == "rotary") && s.exit in 1..12
        val (type, res) = when {
            s.direction == "arrive" -> Maneuver.TYPE_DESTINATION to R.drawable.ic_flag
            roundabout -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW to R.drawable.ic_roundabout
            s.direction == "uturn" -> Maneuver.TYPE_U_TURN_LEFT to R.drawable.ic_uturn
            s.direction == "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT to R.drawable.ic_turn_left
            s.direction == "slight left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT to R.drawable.ic_turn_left
            s.direction.contains("left") -> Maneuver.TYPE_TURN_NORMAL_LEFT to R.drawable.ic_turn_left
            s.direction == "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT to R.drawable.ic_turn_right
            s.direction == "slight right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT to R.drawable.ic_turn_right
            s.direction.contains("right") -> Maneuver.TYPE_TURN_NORMAL_RIGHT to R.drawable.ic_turn_right
            else -> Maneuver.TYPE_STRAIGHT to R.drawable.ic_straight
        }
        val mb = Maneuver.Builder(type).setIcon(icon(carContext, res))
        if (roundabout) mb.setRoundaboutExitNumber(s.exit)
        val b = Step.Builder(s.text).setManeuver(mb.build())
        if (s.road.isNotBlank()) b.setRoad(s.road)
        val lanes = s.lanes
        if (withLanes && lanes != null) {
            for (l in lanes) {
                val lb = Lane.Builder()
                val dirs = l.directions.ifEmpty { listOf("straight") }
                for (dname in dirs) lb.addDirection(LaneDirection.create(laneShape(dname), l.valid && (dname == s.direction || dirs.size == 1)))
                b.addLane(lb.build())
            }
            if (lanesFor !== s) { lanesFor = s; lanesIcon = CarIcon.Builder(IconCompat.createWithBitmap(laneBitmap(lanes, s.direction))).build() }
            lanesIcon?.let { b.setLanesImage(it) }
        }
        return b.build()
    }

    private fun laneShape(d: String) = when (d) {
        "uturn" -> LaneDirection.SHAPE_U_TURN_LEFT
        "sharp left" -> LaneDirection.SHAPE_SHARP_LEFT
        "left" -> LaneDirection.SHAPE_NORMAL_LEFT
        "slight left" -> LaneDirection.SHAPE_SLIGHT_LEFT
        "sharp right" -> LaneDirection.SHAPE_SHARP_RIGHT
        "right" -> LaneDirection.SHAPE_NORMAL_RIGHT
        "slight right" -> LaneDirection.SHAPE_SLIGHT_RIGHT
        "straight", "none" -> LaneDirection.SHAPE_STRAIGHT
        else -> LaneDirection.SHAPE_UNKNOWN
    }

    /** Picture of the lanes for the car's turn card: white arrows on lanes to use, grey on others. */
    private fun laneBitmap(lanes: List<gr.gamemap.Lane>, maneuver: String): Bitmap {
        val cell = 64
        val h = 64
        val bmp = Bitmap.createBitmap(cell * lanes.size, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        lanes.forEachIndexed { i, l ->
            val dir = l.directions.firstOrNull { l.valid && it == maneuver }
                ?: l.directions.firstOrNull { l.valid && it != "none" } ?: l.directions.firstOrNull() ?: "straight"
            p.color = if (l.valid) Color.WHITE else Color.argb(110, 255, 255, 255)
            val x = i * cell + cell / 2f; val y = h / 2f; val s = 22f
            val ang = when {
                dir == "uturn" -> 180.0
                dir.contains("sharp left") -> -135.0
                dir.contains("slight left") -> -40.0
                dir.contains("left") -> -90.0
                dir.contains("sharp right") -> 135.0
                dir.contains("slight right") -> 40.0
                dir.contains("right") -> 90.0
                else -> 0.0
            }
            val path = Path()
            path.moveTo(x, y + s)
            val a = Math.toRadians(ang - 90)
            val ex: Float; val ey: Float
            if (ang == 0.0) { ex = x; ey = y - s } else {
                path.lineTo(x, y)
                ex = x + (s * Math.cos(a)).toFloat(); ey = y + (s * Math.sin(a)).toFloat()
            }
            path.lineTo(ex, ey)
            val back = a + Math.PI
            path.moveTo(ex + (s * 0.5 * Math.cos(back + 0.6)).toFloat(), ey + (s * 0.5 * Math.sin(back + 0.6)).toFloat())
            path.lineTo(ex, ey)
            path.lineTo(ex + (s * 0.5 * Math.cos(back - 0.6)).toFloat(), ey + (s * 0.5 * Math.sin(back - 0.6)).toFloat())
            c.drawPath(path, p)
        }
        return bmp
    }

    companion object {
        const val OFFER_ALERT = 7
        const val ASK_ALERT = 8
    }
}

/** Route choices on the car screen: pick one and Start (the map shows them all). */
class RoutePreviewScreen(carContext: CarContext) : Screen(carContext) {
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private var lastInvalidate = 0L
    private val refresh = Runnable { lastInvalidate = System.currentTimeMillis(); invalidate() }
    private val listener: () -> Unit = {
        if (!NavEngine.planning) {
            started = true
            finish()
        } else {
            main.removeCallbacks(refresh)
            val wait = 800 - (System.currentTimeMillis() - lastInvalidate)
            if (wait <= 0) refresh.run() else main.postDelayed(refresh, wait)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { NavEngine.changed += listener }
            override fun onDestroy(owner: LifecycleOwner) {
                NavEngine.changed -= listener
                main.removeCallbacks(refresh)
                if (!started && NavEngine.planning) NavEngine.cancelPlan()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val b = RoutePreviewNavigationTemplate.Builder()
            .setTitle(NavEngine.planPlace?.name ?: gr.gamemap.L.t("Route"))
            .setHeaderAction(Action.BACK)
        val opts = NavEngine.options
        if (opts.isEmpty() || opts.any { !NavEngine.timeKnown(it) }) {
            b.setLoading(true)
            return b.build()
        }
        val list = ItemList.Builder()
        val fastest = NavEngine.fastestIndex()
        opts.forEachIndexed { i, o ->
            val title = SpannableString(" ")
            title.setSpan(DurationSpan.create(o.seconds.toLong()), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val toll = if (o.tollCost > 0) " · " + gr.gamemap.L.t("${gr.gamemap.Tolls.money(o.tollCost)} tolls") else if (o.hasToll) " · " + gr.gamemap.L.t("tolls") else ""
            val line = SpannableString(" · " + gr.gamemap.L.t(if (o.via.isNotBlank()) "via ${o.via}" else "route ${i + 1}") + toll)
            line.setSpan(DistanceSpan.create(carDistance(o.distance)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val row = Row.Builder().setTitle(title).addText(line)
            when {
                o.trafficSeconds != null && i == fastest && opts.size > 1 -> row.addText(gr.gamemap.L.t("Fastest"))
                o.delaySeconds >= 60 -> row.addText(gr.gamemap.L.t("+${o.delaySeconds / 60} min traffic"))
            }
            list.addItem(row.build())
        }
        list.setSelectedIndex(NavEngine.selected.coerceIn(0, opts.size - 1))
        list.setOnSelectedListener { NavEngine.select(it) }
        b.setItemList(list.build())
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_4) {
            b.setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        }
        b.setNavigateAction(
            Action.Builder().setTitle(gr.gamemap.L.t("Start")).setOnClickListener {
                started = true
                NavEngine.startPlanned()
                screenManager.popToRoot()
            }.build()
        )
        return b.build()
    }
}

fun carDistance(m: Double): Distance = when {
    m >= 10_000 -> Distance.create((m / 1000).toInt().toDouble(), Distance.UNIT_KILOMETERS)
    m >= 1000 -> Distance.create(Math.round(m / 100) / 10.0, Distance.UNIT_KILOMETERS_P1)
    m >= 100 -> Distance.create(((m / 50).toInt() * 50).toDouble(), Distance.UNIT_METERS)
    else -> Distance.create(((m / 10).toInt() * 10).toDouble(), Distance.UNIT_METERS)
}

/** Destination search on the car screen: recents first, live suggestions while typing. */
/** Before typing in the car: saved places first, then recent ones. */
private fun carStart(): List<Place> {
    val fav = listOfNotNull(Places.saved("home"), Places.saved("work")) + Places.labelled().map { it.place() } +
        Places.savedList().filter { it.label.isNullOrBlank() }.map { it.place() }
    return (fav + Places.recents().filter { r -> fav.none { gr.gamemap.Geo.distance(it.pos, r.pos) < 25 } }).take(8)
}

class SearchScreen(carContext: CarContext) : Screen(carContext) {
    private var results: List<Place> = carStart()
    private var loading = false
    private var message = gr.gamemap.L.t("Type a destination")
    private var seq = 0
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    init { LocationTracker.position?.let { Places.warmUp(it, force = true) } }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                main.removeCallbacksAndMessages(null)
                if (searchText.trim().length < 2) {
                    seq++
                    results = carStart(); loading = false; invalidate()
                } else {
                    main.postDelayed({ search(searchText, full = false) }, 500)
                }
            }

            override fun onSearchSubmitted(searchText: String) {
                main.removeCallbacksAndMessages(null)
                search(searchText, full = true)
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(gr.gamemap.L.t("Where to?"))
            .setShowKeyboardByDefault(false)
        if (loading && results.isEmpty()) {
            b.setLoading(true)
        } else {
            val near = LocationTracker.position
            val list = ItemList.Builder().setNoItemsMessage(message)
            results.take(6).forEach { p ->
                val row = Row.Builder().setTitle(p.name).setOnClickListener {
                    screenManager.pop()
                    NavEngine.plan(carContext, p)
                }
                val dist = near?.let { NavEngine.formatDistance(gr.gamemap.Geo.distance(it, p.pos)) }
                val line = listOfNotNull(dist, p.address.takeIf { it.isNotBlank() }?.take(70)).joinToString(" · ")
                if (line.isNotBlank()) row.addText(line)
                list.addItem(row.build())
            }
            b.setItemList(list.build())
        }
        return b.build()
    }

    private fun search(q: String, full: Boolean) {
        if (q.isBlank()) return
        val my = ++seq
        loading = true
        invalidate()
        val near = LocationTracker.position
        io.execute {
            val r = try { Places.find(q, near, full) } catch (e: Exception) { null }
            main.post {
                if (my != seq) return@post
                loading = false
                results = r ?: emptyList()
                message = if (r == null) gr.gamemap.L.t("Search failed: no internet?") else gr.gamemap.L.t("Nothing found")
                invalidate()
            }
        }
    }
}

/** Report police, accidents and hazards from the car. */
class ReportScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        // a camera here, kept on the phone (works without the shared reports)
        for ((k, title) in listOf("speed" to gr.gamemap.L.t("📷  Speed camera here"), "redlight" to gr.gamemap.L.t("🚦  Red-light camera here"))) {
            list.addItem(Row.Builder().setTitle(title).addText(gr.gamemap.L.t("Saved on your phone: you'll be warned next time")).setOnClickListener {
                val ok = gr.gamemap.Alerts.markCamera(k)
                CarToast.makeText(carContext, if (ok) gr.gamemap.L.t("Camera marked") else gr.gamemap.L.t("Waiting for your location"), CarToast.LENGTH_SHORT).show()
                screenManager.pop()
            }.build())
        }
        if (gr.gamemap.Alerts.reportsReady) for (t in gr.gamemap.Alerts.reportTypes.take(4)) {
            list.addItem(Row.Builder().setTitle("${t.glyph}  ${t.title}").setOnClickListener {
                gr.gamemap.Alerts.report(t.key) { ok ->
                    CarToast.makeText(carContext, if (ok) "Thanks! ${t.title} reported" else "Couldn't send the report", CarToast.LENGTH_SHORT).show()
                }
                screenManager.pop()
            }.build())
        }
        return ListTemplate.Builder().setTitle(gr.gamemap.L.t("Report")).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/** The ☰ menu: everything the map buttons do, as a list (also for cars that don't report taps). */
class CarMenuScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        fun row(title: String, text: String? = null, onClick: () -> Unit) {
            val r = Row.Builder().setTitle(title).setOnClickListener(onClick)
            if (text != null) r.addText(text)
            list.addItem(r.build())
        }
        if (NavEngine.lastTrip != null) {
            row(gr.gamemap.L.t("Done"), NavEngine.lastTrip?.place) { NavEngine.stop(); NavEngine.dismissTrip(); screenManager.pop() }
            row(gr.gamemap.L.t("Parking nearby")) {
                NavEngine.stop(); NavEngine.dismissTrip(); screenManager.pop()
                screenManager.push(NearbyScreen(carContext, gr.gamemap.L.t("Parking nearby"), listOf("parking")))
            }
        } else {
            if (NavEngine.active) row(gr.gamemap.L.t("End navigation"), NavEngine.destinationName) { NavEngine.stop(); screenManager.pop() }
            else row(gr.gamemap.L.t("Search")) { screenManager.pop(); screenManager.push(SearchScreen(carContext)) }
            // (the list holds 6 rows; Recenter is on the map itself)
            val cd = gr.gamemap.Convoy.destination
            val lobby = gr.gamemap.Convoy.lobby()
            if (gr.gamemap.Convoy.active && !NavEngine.active && cd != null && lobby.isNotEmpty()) {
                // the race start: ready here too; the countdown shows on the map
                val waiting = lobby.filter { !it.ready }.map { it.name }
                if (gr.gamemap.Convoy.meReady) row(gr.gamemap.L.t("✓ Ready (tap to undo)"),
                    if (waiting.isEmpty()) gr.gamemap.L.t("Everyone's ready!") else gr.gamemap.L.t("Waiting for ") + waiting.joinToString(", ")) {
                    gr.gamemap.Convoy.setReady(false); invalidate()
                } else row(gr.gamemap.L.t("🏁 I'm ready"), cd.name) { gr.gamemap.Convoy.setReady(true); screenManager.pop() }
            } else if (gr.gamemap.Convoy.active && !NavEngine.active && cd != null) row(gr.gamemap.L.t("Convoy: go"), cd.name) {
                screenManager.pop(); NavEngine.start(carContext, cd)
            } else row(gr.gamemap.L.t("Fuel nearby"), if (NavEngine.active) gr.gamemap.L.t("Add a fuel stop") else gr.gamemap.L.t("Prices and ratings")) {
                screenManager.pop(); screenManager.push(NearbyScreen(carContext, gr.gamemap.L.t("Fuel nearby"), listOf("fuel", "charging_station")))
            }
        }
        if (gr.gamemap.Convoy.active) row(gr.gamemap.L.t("Leave convoy"), "${gr.gamemap.Convoy.members.size + 1} drivers") { gr.gamemap.Convoy.leave(); screenManager.pop() }
        else row(gr.gamemap.L.t("Map style"), (MapState.carTheme ?: MapState.theme).title) { MapState.nextCarTheme(); invalidate() }
        val stop = NavEngine.stops.firstOrNull()
        if (stop != null && NavEngine.active) row(gr.gamemap.L.t("Remove stop"), stop.name) { NavEngine.removeStop(stop); screenManager.pop() }
        else row("Traffic", if (gr.gamemap.Traffic.showLayer) gr.gamemap.L.t("On") else gr.gamemap.L.t("Off")) { gr.gamemap.Traffic.showLayer = !gr.gamemap.Traffic.showLayer; invalidate() }
        row(gr.gamemap.L.t("Spoken directions"), if (Voice.muted) gr.gamemap.L.t("Off") else gr.gamemap.L.t("On")) { Voice.muted = !Voice.muted; invalidate() }
        row(gr.gamemap.L.t("Report")) { screenManager.pop(); screenManager.push(ReportScreen(carContext)) }
        return ListTemplate.Builder().setTitle("GameRoute").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/** Places of one kind around you (e.g. parking after arriving): pick one to go there. */
class NearbyScreen(carContext: CarContext, private val title: String, private val types: List<String>) : Screen(carContext) {
    private val isFuel = "fuel" in types
    private var results: List<Place>? = null
    private var failed = false

    init {
        val near = LocationTracker.position
        if (near == null) failed = true
        else Executors.newSingleThreadExecutor().execute {
            val main = Handler(Looper.getMainLooper())
            // 1) instant, from the map data on the phone
            val local = try { Places.nearbyLocal(types, near) } catch (e: Exception) { emptyList() }
            if (local.isNotEmpty()) main.post { results = local; invalidate() }
            // 2) the online services add what the map data doesn't have
            val r = try { if (isFuel) Places.fuelAndChargers(near, gr.gamemap.Fuel.showChargers) else Places.nearbyFull(types, near, local) } catch (e: Exception) { null }
            main.post {
                results = r ?: local
                failed = r == null && local.isEmpty()
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val F = gr.gamemap.Fuel
        val head = if (isFuel) gr.gamemap.L.t("Fuel") + " · " + F.nameOf(F.chosen) + " · " + gr.gamemap.L.t(when (F.sort) { "nearest" -> "nearest"; "route" -> "on my route"; else -> "cheapest" }) else title
        val b = ListTemplate.Builder().setTitle(head).setHeaderAction(Action.BACK)
        if (isFuel) {
            // fuel type and sort, as the two buttons up top
            val kinds = F.fuelKinds.filter { it.first in F.myFuels }.map { it.first } + (if (F.showChargers) listOf("ev") else emptyList())
            val sorts = listOfNotNull("cheapest", "nearest", if (NavEngine.active && !NavEngine.arrived) "route" else null)
            b.setActionStrip(ActionStrip.Builder()
                .addAction(Action.Builder().setTitle(if (F.chosen == "ev") "⚡ EV" else F.nameOf(F.chosen)).setOnClickListener {
                    F.chosen = kinds[(kinds.indexOf(F.chosen) + 1) % kinds.size]; invalidate()
                }.build())
                .addAction(Action.Builder().setTitle(gr.gamemap.L.t(when (F.sort) { "nearest" -> "Nearest"; "route" -> "On my route"; else -> "Cheapest" })).setOnClickListener {
                    F.sort = sorts[(sorts.indexOf(F.sort).coerceAtLeast(0) + 1) % sorts.size]; invalidate()
                }.build())
                .build())
        }
        val r0 = results
        if (r0 == null && !failed) return b.setLoading(true).build()
        val near = LocationTracker.position
        val r = if (isFuel && near != null) F.arrange(r0.orEmpty(), near) else r0
        val list = ItemList.Builder().setNoItemsMessage(if (failed) gr.gamemap.L.t("Couldn't search: no location or internet") else gr.gamemap.L.t("Nothing found nearby"))
        r.orEmpty().take(6).forEach { p ->
            val row = Row.Builder().setTitle(p.name).setOnClickListener {
                screenManager.pop()
                // while navigating, a fuel station or café becomes a stop on the way
                if (NavEngine.active && !NavEngine.arrived) NavEngine.addStop(p) else NavEngine.plan(carContext, p)
            }
            val line = listOfNotNull(
                near?.let { NavEngine.formatDistance(gr.gamemap.Geo.distance(it, p.pos)) },
                gr.gamemap.Reviews.stars(p.rating, p.ratings),
            ).joinToString(" · ")
            if (line.isNotEmpty()) row.addText(line)
            if (isFuel) {
                // your fuel's price first (or a charger's power)
                val price = if (F.isCharger(p)) (if (p.kw > 0) "⚡ ${p.kw} kW" else "⚡ " + gr.gamemap.L.t("charger"))
                    else F.priceOf(p, F.chosen)?.let { "${F.nameOf(F.chosen)} €${"%.3f".format(java.util.Locale.US, it)}" + if (p.areaAvg) " (" + gr.gamemap.L.t("area avg.") + ")" else "" }
                val det = F.detour(p)?.let { " · +${maxOf(1, (it / 12 / 60).toInt() + 1)} min" }.orEmpty()
                if (price != null) row.addText(price + det) else if (det.isNotEmpty()) row.addText(det.removePrefix(" · "))
            } else if (p.extra.isNotBlank()) row.addText(gr.gamemap.Fuel.brief(p.extra))
            list.addItem(row.build())
        }
        return b.setSingleList(list.build()).build()
    }
}
