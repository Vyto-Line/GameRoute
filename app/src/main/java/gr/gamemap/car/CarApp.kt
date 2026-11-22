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
import androidx.car.app.navigation.model.Trip
import androidx.car.app.navigation.model.Destination
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
        MapState.car.startDrive()
        gr.gamemap.Duties.refresh()
        gr.gamemap.CrashLog.note("Android Auto opened")
        gr.gamemap.CrashLog.watchMainThread() // a freeze in the car leaves a report too
        gr.gamemap.Parked.clear() // driving off: the old parking spot is history
        // the car's night mode follows its headlights (or light sensor): dark map at night
        MapState.setCarNight(carContext.isDarkMode)
        val stopCarData = listenToCar(carContext)
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                stopCarData()
                gr.gamemap.CarFeed.clear()
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

/**
 * The car's speed and, where the car shares it, its own GPS (Car App API 3+). Each piece is optional:
 * a car (or a host) that doesn't offer one just doesn't call back. Returns how to stop listening.
 */
fun listenToCar(ctx: CarContext): () -> Unit {
    if (ctx.carAppApiLevel < 3) return {}
    val stops = ArrayList<() -> Unit>()
    val exec = androidx.core.content.ContextCompat.getMainExecutor(ctx)
    try {
        val hw = ctx.getCarService(androidx.car.app.hardware.CarHardwareManager::class.java)
        try {
            val l = androidx.car.app.hardware.common.OnCarDataAvailableListener<androidx.car.app.hardware.info.Speed> { sp ->
                val v = sp.rawSpeedMetersPerSecond
                if (v.status == androidx.car.app.hardware.common.CarValue.STATUS_SUCCESS) v.value?.let { gr.gamemap.CarFeed.onSpeed(kotlin.math.abs(it)) }
            }
            hw.carInfo.addSpeedListener(exec, l)
            stops += { try { hw.carInfo.removeSpeedListener(l) } catch (_: Exception) {} }
            gr.gamemap.CrashLog.note("car speed: listening")
        } catch (e: Exception) { gr.gamemap.CrashLog.note("car speed: $e") }
        try {
            val l = androidx.car.app.hardware.common.OnCarDataAvailableListener<androidx.car.app.hardware.info.CarHardwareLocation> { loc ->
                val v = loc.location
                if (v.status == androidx.car.app.hardware.common.CarValue.STATUS_SUCCESS) v.value?.let { gr.gamemap.CarFeed.onLocation(it) }
            }
            hw.carSensors.addCarHardwareLocationListener(androidx.car.app.hardware.info.CarSensors.UPDATE_RATE_NORMAL, exec, l)
            stops += { try { hw.carSensors.removeCarHardwareLocationListener(l) } catch (_: Exception) {} }
        } catch (e: Exception) { gr.gamemap.CrashLog.note("car location: $e") }
    } catch (e: Exception) { gr.gamemap.CrashLog.note("car hardware: $e") }
    return { stops.forEach { it() } }
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
    /** Called (main thread) when the car's style or light/dark changes, so Android Auto's ☰ is recoloured. */
    var onStyle: (() -> Unit)? = null
    @Volatile private var styleKey = ""
    val ownButtons get() = renderer.carButtons
    private val main = Handler(Looper.getMainLooper())
    /**
     * Drawing has its own thread: the main thread stays free for the car's taps and drags, so
     * they're handled at once instead of waiting behind a frame.
     */
    @Volatile private var thread = newThread()
    @Volatile private var rh = Handler(thread.looper)
    private fun newThread() = android.os.HandlerThread("car-render", android.os.Process.THREAD_PRIORITY_DISPLAY).apply { start() }

    /**
     * Held while a frame is drawn. The car's own calls (screen gone, new size, visible area) never
     * wait on it for long: a frame stuck on a screen the car is taking away (it stops taking frames
     * first) used to hold the main thread too, and GameRoute froze in the car after a few switches.
     */
    @Volatile private var drawing = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var frameStarted = 0L

    @Volatile private var surface: Surface? = null
    @Volatile private var w = 0
    @Volatile private var h = 0
    @Volatile private var density = 1f
    /** The part of the screen not covered by Android Auto (cards, bars): a fresh copy each change. */
    @Volatile private var visible = Rect()
    private val draw = Runnable { render() }
    val redraw: () -> Unit = { val r = rh; r.removeCallbacks(draw); r.post(draw) }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        // the last frame never finished (stuck on the old screen): draw on a fresh thread
        if (frameStarted != 0L && android.os.SystemClock.uptimeMillis() - frameStarted > 1500) {
            gr.gamemap.CrashLog.note("car: a frame was stuck, new drawing thread")
            val old = thread
            drawing = java.util.concurrent.locks.ReentrantLock()
            thread = newThread(); rh = Handler(thread.looper)
            frameStarted = 0L
            old.quit()
        }
        w = container.width; h = container.height
        density = (container.dpi / 160f).coerceAtLeast(0.75f)
        visible = Rect(0, 0, w, h)
        hardware = true
        surface = container.surface
        gr.gamemap.CrashLog.note("car screen ${w}x$h")
        redraw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        gr.gamemap.CrashLog.note("car visible area $visibleArea of ${w}x$h")
        visible = Rect(visibleArea)
        redraw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        renderer.stableTop = stableArea.top
        redraw()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        // no new frames on it; a frame being drawn gets a moment to finish, but the car is never
        // kept waiting (it may have stopped taking frames already, and would wait for us)
        surface = null
        val lk = drawing
        if (lk.tryLock(300, java.util.concurrent.TimeUnit.MILLISECONDS)) lk.unlock()
        else gr.gamemap.CrashLog.note("car: screen gone during a frame")
    }

    /** Friends on the map: open at a page ("list", "convoy") or close (null). */
    fun openFriends(page: String?) { renderer.carFriends = page; if (page != null) renderer.carPanel = null; redraw() }
    /** A panel in the style on the map (null: closed). */
    fun showPanel(p: gr.gamemap.CarPanel?) { if (p?.key != renderer.carPanel?.key) renderer.carPanelPage = 0; renderer.carPanel = p; if (p != null) renderer.carFriends = null; redraw() }
    val panelKey get() = renderer.carPanel?.key
    fun panelPage(delta: Int) { renderer.carPanelPage = (renderer.carPanelPage + delta).coerceAtLeast(0); redraw() }
    /** A short note in the style ("Sent", "Reported"). */
    fun flash(msg: String) { renderer.carFlash = msg to System.currentTimeMillis(); redraw() }
    val friendsOpen get() = renderer.carFriends != null
    /** ‹ / the car's BACK: the convoy page goes back to the list, the list closes. */
    fun friendsBack() { renderer.carFriends = if (renderer.carFriends == "convoy") "list" else null; redraw() }

    /** The car screen went away for good: stop the drawing thread. */
    fun release() {
        surface = null
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

    @Volatile private var hardware = true

    private fun render() {
        val lk = drawing
        if (!lk.tryLock()) { rh.postDelayed(draw, 50); return } // the screen is changing: try again in a moment
        try {
            val s = surface ?: return
            if (!s.isValid || w == 0) return
            frameStarted = android.os.SystemClock.uptimeMillis()
            val c = try {
                if (hardware) s.lockHardwareCanvas() else s.lockCanvas(null)
            } catch (e: Exception) {
                if (hardware) { hardware = false; redraw() }
                return
            }
            try {
                val t0 = android.os.SystemClock.uptimeMillis()
                val v = visible
                renderer.draw(c, w, h, if (v.isEmpty) Rect(0, 0, w, h) else v, density, hud = true)
                val sk = "${MapState.carTheme ?: MapState.theme}|${MapState.dark}"
                if (sk != styleKey) { val first = styleKey.isEmpty(); styleKey = sk; if (!first) main.post { onStyle?.invoke() } }
                gr.gamemap.Perf.frameDrawn(android.os.SystemClock.uptimeMillis() - t0)
            } catch (e: Exception) {
                // a frame drawn while the data changed under it: skip it, the next one is fine
            } catch (e: OutOfMemoryError) {
                // out of memory: give back the map caches and carry on (the next frame reloads)
                gr.gamemap.CrashLog.note("car frame: out of memory, caches trimmed")
                try { gr.gamemap.TileCache.trim(0.3); gr.gamemap.VectorSource.trim(0.5) } catch (_: Throwable) {}
            } finally {
                try { s.unlockCanvasAndPost(c) } catch (_: Exception) {}
            }
        } finally {
            frameStarted = 0L
            lk.unlock()
        }
        // Keep animating (gliding arrow, smooth zoom) at ~30 fps while something moves.
        val r = rh
        if (renderer.animating) {
            r.removeCallbacks(draw)
            r.postDelayed(draw, if (renderer.interacting) 16L else gr.gamemap.Perf.frameDelay(LocationTracker.speedMps > 1f))
        } else if (renderer.effectsMoving) {
            r.removeCallbacks(draw)
            r.postDelayed(draw, gr.gamemap.Perf.effectsDelay())
        }
    }
}

class MapScreen(carContext: CarContext) : Screen(carContext) {
    private val surface = CarMapSurface(carContext)
    /** The car's BACK steps back through the Friends panel while it's open (otherwise as usual). */
    private val backFriends = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { surface.friendsBack(); isEnabled = surface.friendsOpen }
    }
    init { carContext.onBackPressedDispatcher.addCallback(this, backFriends) }

    // ------------------------------------------------------------ panels drawn in the style
    // (cars that report taps; the others keep Android Auto's lists, which work with a knob)

    private val panelActions = HashMap<String, () -> Unit>()
    private val backPanel = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { closePanel() }
    }
    init { carContext.onBackPressedDispatcher.addCallback(this, backPanel) }

    private fun closePanel() { panelActions.clear(); surface.showPanel(null); backPanel.isEnabled = false }

    private fun show(p: gr.gamemap.CarPanel, actions: Map<String, () -> Unit>) {
        panelActions.clear(); panelActions.putAll(actions)
        surface.showPanel(p); backPanel.isEnabled = true
    }

    private fun onPanel(id: String) {
        when (id) {
            "back" -> closePanel()
            "none" -> {}
            "page-" -> surface.panelPage(-1)
            "page+" -> surface.panelPage(1)
            else -> panelActions[id]?.invoke()
        }
    }

    private fun T(s: String) = gr.gamemap.L.t(s)

    /** The ◈ menu, as a panel. */
    private fun openMenuPanel() {
        val C = gr.gamemap.Convoy; val F = gr.gamemap.Friends
        val rows = ArrayList<gr.gamemap.CarRow>(); val acts = HashMap<String, () -> Unit>()
        fun row(id: String, icon: String, title: String, sub: String? = null, right: String? = null, act: () -> Unit) {
            rows += gr.gamemap.CarRow(icon, title, sub, right, id); acts[id] = act
        }
        if (NavEngine.lastTrip != null) {
            row("done", "✓", T("Done"), NavEngine.lastTrip?.place) { closePanel(); NavEngine.stop(); NavEngine.dismissTrip() }
            row("parking", "🅿️", T("Parking nearby")) { NavEngine.stop(); NavEngine.dismissTrip(); openNearbyPanel(false) }
        } else {
            if (NavEngine.active) row("end", "⏹", T("End navigation"), NavEngine.destinationName) { closePanel(); NavEngine.stop() }
            else row("search", "🔍", T("Search")) { closePanel(); screenManager.push(SearchScreen(carContext)) }
            val cd = C.destination
            val lobby = C.lobby()
            if (C.active && !NavEngine.active && cd != null && lobby.isNotEmpty()) {
                val waiting = lobby.filter { !it.ready }.map { it.name }
                if (C.meReady) row("unready", "✓", T("Ready (tap to undo)"), if (waiting.isEmpty()) T("Everyone's ready!") else T("Waiting for ") + waiting.joinToString(", ")) { C.setReady(false); openMenuPanel() }
                else row("ready", "🏁", T("I'm ready"), cd.name) { closePanel(); C.setReady(true) }
                if (!C.counting) row("along", "🚗", T("Drive along"), T("Same route, no race")) { closePanel(); C.setAlong(true); NavEngine.start(carContext, cd) }
            } else if (C.active && !NavEngine.active && cd != null) row("go", "🏁", T("Convoy: go"), cd.name) { closePanel(); NavEngine.start(carContext, cd) }
            else row("fuel", "⛽", T("Fuel nearby"), if (NavEngine.active) T("Add a fuel stop") else T("Prices and ratings")) { openNearbyPanel(true) }
            val onDuty = LocationTracker.position?.let { gr.gamemap.Duties.onDutyNear(it, 5000.0).size } ?: 0
            rows += gr.gamemap.CarRow("💊", T("Pharmacies"), if (onDuty > 0) "🌙 " + T(if (onDuty == 1) "1 on duty now" else "$onDuty on duty now") else null,
                id = "pharmacy", moon = onDuty > 0)
            acts["pharmacy"] = { openPharmacyPanel() }
        }
        if (F.ready && F.core.started) {
            val inv = F.core.invites.firstOrNull { it.convoy != C.code }
            val online = F.core.friends.count { F.core.isOnline(it.fid) }
            row("friends", if (inv != null) "🔔" else "👥", T("Friends"), when {
                inv != null -> T("${inv.from} invites you to a convoy")
                online > 0 -> T("$online friends online")
                F.core.friends.isNotEmpty() -> T("Invite friends")
                else -> T("No friends yet")
            }) { closePanel(); surface.openFriends(if (C.active) "convoy" else "list"); backFriends.isEnabled = true }
        }
        if (C.active && C.members.isNotEmpty()) row("msg", "💬", T("Message the convoy"), T("Need fuel, Stopping soon…")) { openQuickPanel() }
        if (C.active) row("leave", "🚪", T("Leave convoy"), T("${C.members.size + 1} drivers")) { openLeavePanel() }
        else row("style", "🗺️", T("Map style"), null, (MapState.carTheme ?: MapState.theme).title) { MapState.nextCarTheme(); openMenuPanel() }
        val stop = NavEngine.stops.firstOrNull()
        if (stop != null && NavEngine.active) row("unstop", "✖", T("Remove stop"), stop.name) { closePanel(); NavEngine.removeStop(stop) }
        row("voice", "🔊", T("Spoken directions"), null, if (Voice.muted) T("Off") else T("On")) { Voice.muted = !Voice.muted; openMenuPanel() }
        show(gr.gamemap.CarPanel("menu", "GameRoute", rows = rows), acts)
    }

    // ---------------------------------------------------------------- the convoy, step by step (47b-47d)

    private fun steps(k: Int) = listOf("Where", "How", "Who").mapIndexed { i, t -> (if (i < k) "●" else "○") + " " + (i + 1) + " · " + T(t) }.joinToString("   ")

    /** Step 1: where are you going together? Big tiles; Search uses the car's keyboard, then comes back. */
    private fun openConvoyWhere() {
        val C = gr.gamemap.Convoy
        val acts = HashMap<String, () -> Unit>()
        val tiles = ArrayList<gr.gamemap.CarRow>()
        val here = LocationTracker.position
        fun dist(p: Place) = here?.let { NavEngine.formatDistance(gr.gamemap.Geo.distance(it, p.pos)) }
        val now = if (NavEngine.active && !NavEngine.arrived) NavEngine.destination?.let { Place(NavEngine.destinationName, "", it) } else NavEngine.planPlace
        fun pick(p: Place) { C.setDestination(p); openConvoyHow() }
        if (now != null) { tiles += gr.gamemap.CarRow("➤", now.name, T("Where you're going now"), id = "now"); acts["now"] = { pick(now) } }
        for ((k, ic) in listOf("home" to "🏠", "work" to "💼")) Places.saved(k)?.let { pl ->
            tiles += gr.gamemap.CarRow(ic, T(if (k == "home") "Home" else "Work"), dist(pl), id = k); acts[k] = { pick(pl) }
        }
        tiles += gr.gamemap.CarRow("🔍", T("Search"), T("A place or an address"), id = "search")
        acts["search"] = { screenManager.push(SearchScreen(carContext) { p -> main.post { pick(p) } }) }
        tiles += gr.gamemap.CarRow("📍", T("No shared place"), T("Just see each other on the map"), id = "share")
        acts["share"] = { C.setSharing(true); closePanel(); surface.openFriends("list"); backFriends.isEnabled = true }
        show(gr.gamemap.CarPanel("cwhere", T("Where are you going together?"), tiles = tiles, note = steps(1)), acts)
    }

    /** Step 2: how do you want to drive? The chosen one marked; Next opens Friends to invite. */
    private fun openConvoyHow(chosen: String? = null) {
        val C = gr.gamemap.Convoy
        val cur = chosen ?: when { C.meSharing -> "share"; C.meAlong -> "along"; else -> "race" }
        val acts = HashMap<String, () -> Unit>()
        fun row(id: String, icon: String, title: String, sub: String) =
            gr.gamemap.CarRow(icon, T(title), T(sub), if (cur == id) "◉" else "○", id, highlight = cur == id).also { acts[id] = { openConvoyHow(id) } }
        val rows = listOf(
            row("race", "🏁", "Race", "3-2-1 together, fastest wins"),
            row("along", "🚗", "Drive together", "Same route, no race"),
            row("share", "📍", "Just share where I am", "Going somewhere else, still on the map"))
        // Next: the button top right (three choices fit on any car screen without a second page)
        val next = gr.gamemap.CarRow("", T(if (C.host) "Next" else "Join") + " ›", id = "next")
        acts["next"] = {
            when (cur) { "race" -> { C.setSharing(false); C.setAlong(false) }; "along" -> C.setAlong(true); else -> C.setSharing(true) }
            closePanel()
            if (C.host && C.members.isEmpty()) { surface.openFriends("list"); backFriends.isEnabled = true }
        }
        show(gr.gamemap.CarPanel("chow", T("How do you want to drive?"), rows = rows, head = next, note = steps(2) + (C.destination?.let { "  ·  " + T("To ${it.name}") } ?: "")), acts)
    }

    /**
     * After you finish a race: your place, the gap to the winner, who's still driving (distance,
     * arrival, speed). It stays and keeps itself up to date until everyone's in (47a).
     */
    fun openRaceBoard() {
        val C = gr.gamemap.Convoy
        val (mine, total) = C.myPlace() ?: return
        val rows = C.rows(0).filter { !it.along }
        val arrived = rows.filter { it.state == "arrived" }.sortedBy { it.arrivedAt }
        val driving = rows.filter { it.state != "arrived" }
        val first = arrived.firstOrNull(); val me = arrived.firstOrNull { it.me }
        val medal = when (mine) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "🏁" }
        val title = medal + " " + T(when (mine) { 1 -> "You won!"; 2 -> "You finished 2nd"; 3 -> "You finished 3rd"; else -> "You finished ${mine}th" })
        fun gap(s: Long) = if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
        val list = ArrayList<gr.gamemap.CarRow>()
        arrived.forEachIndexed { i, r ->
            list += gr.gamemap.CarRow("${i + 1}", if (r.me) T("You") else r.name,
                T("Arrived ") + NavEngine.clock(r.arrivedAt) + (if (i > 0 && first != null) " · +" + gap((r.arrivedAt - first.arrivedAt) / 1000) else ""), "🏁", "none", highlight = r.me)
        }
        for (r in driving) list += gr.gamemap.CarRow("·", r.name, if (r.stale) T("no signal") else NavEngine.formatDistance(r.left.coerceAtLeast(0.0)) + " " + T("to go") +
            (if (r.eta > 0) " · ~" + NavEngine.clock(r.eta) else ""), "${r.kmh} km/h", "none")
        list += gr.gamemap.CarRow("✓", T("Done"), null, null, "done")
        val sub = listOfNotNull(if (me != null && first != null && !first.me) "+" + gap((me.arrivedAt - first.arrivedAt) / 1000) + " · " + T("Winner: ") + first.name else null,
            if (driving.isEmpty()) T("FINAL ORDER · $total RACERS") else T("LIVE · ${driving.size} STILL DRIVING")).joinToString(" · ")
        raceBoardSeen = C.code
        show(gr.gamemap.CarPanel("race", title, rows = list, note = sub), mapOf("done" to { closePanel() }, "none" to {}))
    }
    private var raceBoardSeen: String? = null
    private var raceBoardAt = 0L

    /** I've finished a race: the board opens once by itself, and stays up to date while it's open. */
    private val onRace: () -> Unit = {
        main.post {
            val C = gr.gamemap.Convoy
            if (C.active && C.myPlace() != null) {
                val now = System.currentTimeMillis()
                if (surface.panelKey == "race") { if (now - raceBoardAt > 1000) { raceBoardAt = now; openRaceBoard() } }
                else if (raceBoardSeen != C.code && surface.panelKey == null) { raceBoardAt = now; openRaceBoard() }
            }
        }
    }

    /** Quick messages to the convoy: four big tiles. */
    private fun openQuickPanel() {
        val C = gr.gamemap.Convoy
        val icons = mapOf("fuel" to "⛽", "stop" to "☕", "ahead" to "👉", "wait" to "✋")
        val acts = HashMap<String, () -> Unit>()
        val tiles = C.quick.map { (k, _, _) ->
            acts["q:$k"] = { C.send(k); closePanel(); surface.flash(T("Sent to the convoy")) }
            gr.gamemap.CarRow(icons[k] ?: "💬", C.messageText(k), id = "q:$k")
        }
        show(gr.gamemap.CarPanel("quick", T("Message the convoy"), tiles = tiles, note = T("Read aloud to everyone driving")), acts)
    }

    /** Report: big tiles (cameras kept on the phone, and the shared reports when they work). */
    private fun openReportPanel() {
        val A = gr.gamemap.Alerts
        val acts = HashMap<String, () -> Unit>()
        val tiles = ArrayList<gr.gamemap.CarRow>()
        for ((k, icon, title) in listOf(Triple("speed", "📷", T("Speed camera")), Triple("redlight", "🚦", T("Red-light camera")))) {
            tiles += gr.gamemap.CarRow(icon, title, id = "cam:$k")
            acts["cam:$k"] = { val ok = A.markCamera(k); closePanel(); surface.flash(if (ok) T("Camera marked") else T("Waiting for your location")) }
        }
        if (A.reportsReady) for (t in A.reportTypes.take(4)) {
            tiles += gr.gamemap.CarRow(t.glyph, T(t.title), id = "rep:${t.key}")
            acts["rep:${t.key}"] = { closePanel(); A.report(t.key) { ok -> main.post { surface.flash(if (ok) T("Thanks! ${t.title} reported") else T("Couldn't send the report")) } } }
        }
        show(gr.gamemap.CarPanel("report", T("Report"), tiles = tiles, note = T("Cameras are saved on your phone: you'll be warned next time")), acts)
    }

    /** Leave the convoy?: asks first, in the style. */
    private fun openLeavePanel() {
        show(gr.gamemap.CarPanel("leave", T("Leave the convoy?"), dialog = true,
            message = T("The others carry on without you. You can join again from an invite."),
            rows = listOf(gr.gamemap.CarRow("", T("Stay"), id = "stay"), gr.gamemap.CarRow("", T("Leave"), id = "go", red = true))),
            mapOf("stay" to { closePanel() }, "go" to { gr.gamemap.Convoy.leave(); closePanel(); surface.flash(T("You left the convoy")) }))
    }

    /** Pharmacies: the ones on duty now first (🌙, till when), then the others (greyed when closed). */
    private fun openPharmacyPanel() {
        val D = gr.gamemap.Duties
        D.refresh()
        val near = LocationTracker.position
        var others: List<Place>? = null
        var first = true
        fun render() {
            if (!first && surface.panelKey != "pharmacy") return
            val now = gr.gamemap.Duties.now()
            val acts = HashMap<String, () -> Unit>()
            val rows = ArrayList<gr.gamemap.CarRow>()
            fun go(p: Place) { closePanel(); if (NavEngine.active && !NavEngine.arrived) NavEngine.addStop(p) else NavEngine.plan(carContext, p) }
            val duties = near?.let { D.onDutyNear(it, 15_000.0, now).take(6) }.orEmpty()
            duties.forEachIndexed { i, dt ->
                val p = D.place(dt, now)
                rows += gr.gamemap.CarRow("💊", p.name, "🌙 " + D.till(dt, now) + (dt.area.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    NavEngine.formatDistance(gr.gamemap.Geo.distance(near!!, dt.pos)), "d:$i", moon = true)
                acts["d:$i"] = { go(p) }
            }
            val closed = D.usuallyClosed(now)
            others.orEmpty().filter { o -> duties.none { gr.gamemap.Geo.distance(it.pos, o.pos) < 60 } }
                .sortedBy { gr.gamemap.Geo.distance(near!!, it.pos) }.take(6).forEachIndexed { i, p ->
                    rows += gr.gamemap.CarRow("💊", p.name, if (closed) T("Closed") + " · " + D.opensLine(now) else p.address.takeIf { it.isNotBlank() },
                        near?.let { NavEngine.formatDistance(gr.gamemap.Geo.distance(it, p.pos)) }, "o:$i", faded = closed)
                    acts["o:$i"] = { go(p) }
                }
            first = false
            val note = if (near != null && D.covers(near)) D.sourceLine(duties.firstOrNull()) else null
            if (rows.isEmpty() && others == null && near != null) { show(gr.gamemap.CarPanel("pharmacy", T("Pharmacies"), loading = true), acts); return }
            show(gr.gamemap.CarPanel("pharmacy", T("Pharmacies"), rows = rows, note = note,
                empty = if (near == null) T("Couldn't search: no location or internet") else T("Nothing found nearby")), acts)
        }
        render()
        if (near != null) carWork.execute {
            val local = try { Places.nearbyLocal(listOf("pharmacy"), near) } catch (e: Exception) { emptyList() }
            if (local.isNotEmpty()) main.post { others = local; render() }
            val r = try { Places.nearbyFull(listOf("pharmacy"), near, local) } catch (e: Exception) { null }
            main.post { others = r ?: local; render() }
        }
    }

    /** Fuel (or parking) nearby: the phone's map data at once, the online prices after. */
    private fun openNearbyPanel(fuel: Boolean) {
        val types = if (fuel) listOf("fuel", "charging_station") else listOf("parking")
        val key = if (fuel) "fuel" else "parking"
        val near = LocationTracker.position
        var results: List<Place>? = null
        var failed = near == null
        fun render() {
            if (surface.panelKey != key && surface.panelKey != null) return
            val F = gr.gamemap.Fuel
            val r0 = results
            val title = if (fuel) T("Fuel") + " · " + F.nameOf(F.chosen) else T("Parking nearby")
            val acts = HashMap<String, () -> Unit>()
            var head: gr.gamemap.CarRow? = null
            if (fuel) {
                val kinds = F.fuelKinds.filter { it.first in F.myFuels }.map { it.first } + (if (F.showChargers) listOf("ev") else emptyList())
                val sorts = listOfNotNull("cheapest", "nearest", if (NavEngine.active && !NavEngine.arrived) "route" else null)
                head = gr.gamemap.CarRow("", T(when (F.sort) { "nearest" -> "Nearest"; "route" -> "On my route"; else -> "Cheapest" }) + " ▾", id = "sort")
                acts["sort"] = { F.sort = sorts[(sorts.indexOf(F.sort).coerceAtLeast(0) + 1) % sorts.size]; render() }
                if (kinds.size > 1) {
                    // the fuel: tapping the title row at the top changes it
                    acts["kind"] = { F.chosen = kinds[(kinds.indexOf(F.chosen) + 1) % kinds.size]; render() }
                }
            }
            if (r0 == null && !failed) { show(gr.gamemap.CarPanel(key, title, head = head, loading = true), acts); return }
            val list = if (fuel && near != null) F.arrange(r0.orEmpty(), near) else r0.orEmpty()
            val rows = ArrayList<gr.gamemap.CarRow>()
            if (fuel && acts.containsKey("kind")) rows += gr.gamemap.CarRow("⛽", T("Fuel: ") + F.nameOf(F.chosen), T("Tap to change"), null, "kind")
            list.take(12).forEachIndexed { i, p ->
                val dist = near?.let { NavEngine.formatDistance(gr.gamemap.Geo.distance(it, p.pos)) }
                val sub = listOfNotNull(dist, gr.gamemap.Reviews.stars(p.rating, p.ratings), if (!fuel && p.extra.isNotBlank()) gr.gamemap.Fuel.brief(p.extra) else null).joinToString(" · ")
                val right = if (fuel) {
                    if (F.isCharger(p)) (if (p.kw > 0) "${p.kw} kW" else "⚡") else F.priceOf(p, F.chosen)?.let { "€" + "%.3f".format(java.util.Locale.US, it) }
                } else null
                val id = "p:$i"
                rows += gr.gamemap.CarRow(if (fuel && F.isCharger(p)) "🔌" else if (fuel) "⛽" else "🅿️", p.name, sub, right, id, highlight = i == 0 && fuel)
                acts[id] = {
                    closePanel()
                    // while navigating it becomes a stop on the way
                    if (NavEngine.active && !NavEngine.arrived) NavEngine.addStop(p) else NavEngine.plan(carContext, p)
                }
            }
            show(gr.gamemap.CarPanel(key, title, rows = rows, head = head,
                empty = if (failed) T("Couldn't search: no location or internet") else T("Nothing found nearby")), acts)
        }
        render()
        if (near != null) carWork.execute {
            val local = try { Places.nearbyLocal(types, near) } catch (e: Exception) { emptyList() }
            if (local.isNotEmpty()) main.post { results = local; render() }
            val r = try { if (fuel) Places.fuelAndChargers(near, gr.gamemap.Fuel.showChargers) else Places.nearbyFull(types, near, local) } catch (e: Exception) { null }
            main.post { results = r ?: local; failed = r == null && local.isEmpty(); render() }
        }
    }
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
            CarToast.makeText(carContext, gr.gamemap.L.t(if (p.place == null) "${p.from} removed the destination" else "${p.from} suggests: ${p.place.name}"), CarToast.LENGTH_LONG).show()
        }
    }

    private val onAlerts: () -> Unit = {
        surface.redraw()
        val r = gr.gamemap.Alerts.askAbout
        if (r != null && r !== askedFor && carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
            askedFor = r
            val t = gr.gamemap.Alerts.typeOf(r.type)
            val alert = Alert.Builder(ASK_ALERT, CarText.create(gr.gamemap.L.t("${t.title} reported here")), 10_000L)
                .setSubtitle(CarText.create(gr.gamemap.L.t("Is it still there?")))
                .addAction(Action.Builder().setTitle(gr.gamemap.L.t("Still there")).setOnClickListener { gr.gamemap.Alerts.vote(r, true); gr.gamemap.Alerts.clearAsk() }.build())
                .addAction(Action.Builder().setTitle(gr.gamemap.L.t("Not there")).setOnClickListener { gr.gamemap.Alerts.vote(r, false); gr.gamemap.Alerts.clearAsk() }.build())
                .build()
            try { carContext.getCarService(AppManager::class.java).showAlert(alert) } catch (_: Exception) {}
        }
    }

    /** The invite last shown (convoy + how many invites), so each is asked once. */
    private var invitedKey = ""

    /** A friend invites me to a convoy: a car alert with Join / Not now. */
    private val onFriends: () -> Unit = {
        if (surface.friendsOpen) surface.redraw()
        val F = gr.gamemap.Friends
        val i = if (F.ready && F.core.started && F.core.allowInvites) F.core.invites.firstOrNull { it.convoy != gr.gamemap.Convoy.code } else null
        val k = i?.let { it.convoy + it.count }.orEmpty()
        if (i != null && k != invitedKey) {
            invitedKey = k
            F.cancelNotification(i.convoy)
            F.markSeen(listOf(i))
            val title = gr.gamemap.L.t("${i.from} invites you to a convoy")
            val sub = if (i.dest.isNotBlank()) i.dest else gr.gamemap.L.t("No destination yet")
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
                val alert = Alert.Builder(FRIEND_ALERT, CarText.create(title), 20_000L)
                    .setSubtitle(CarText.create(sub))
                    .addAction(Action.Builder().setTitle(gr.gamemap.L.t("Join")).setOnClickListener { carJoinConvoy(carContext, i.convoy) }.build())
                    .addAction(Action.Builder().setTitle(gr.gamemap.L.t("Not now")).setOnClickListener { F.answer(i.convoy, "no") }.build())
                    .build()
                try { carContext.getCarService(AppManager::class.java).showAlert(alert) } catch (_: Exception) {}
            } else {
                // older cars: say it, and the ☰ menu's Friends row has Join
                CarToast.makeText(carContext, "$title · ☰", CarToast.LENGTH_LONG).show()
            }
        }
    }

    private var lastFollowing = true
    private val onMapState: () -> Unit = {
        val f = MapState.car.following && MapState.car.userBearing == null
        if (f != lastFollowing) { lastFollowing = f; onNav() }
    }

    private var previewShown = false
    private var alertFor: NavEngine.Offer? = null

    private var shownEndNote: String? = null

    private val onNav: () -> Unit = {
        syncNavigationState()
        // the route stops where the car road ends: say so once
        val note = NavEngine.endNote
        if (note != null && note != shownEndNote) CarToast.makeText(carContext, note, CarToast.LENGTH_LONG).show()
        shownEndNote = note
        // a route preview started from the phone or the car search shows here too
        if (NavEngine.planning && !previewShown) {
            previewShown = true
            // cars that report taps: GameRoute's own route cards on the map (in the style's
            // colours); older ones: Android Auto's own list
            if (surface.ownButtons) invalidate() else screenManager.push(RoutePreviewScreen(carContext))
        } else if (!NavEngine.planning && previewShown) {
            previewShown = false
            if (surface.ownButtons) invalidate()
        }
        syncOfferAlert()
        main.removeCallbacks(refresh)
        val wait = 1000 - (System.currentTimeMillis() - lastInvalidate)
        if (wait <= 0) refresh.run() else main.postDelayed(refresh, wait)
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surface)
        surface.onButton = { onCarButton(it) }
        surface.onStyle = { main.removeCallbacks(refresh); main.post(refresh) }
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            // Android Auto stops it (the car app closing, another navigation app): it comes back
            // when GameRoute's car screen opens again
            override fun onStopNavigation() = NavEngine.stop(keepResume = true)
        })
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                TileCache.loaded += surface.redraw
                LocationTracker.changed += surface.redraw
                MapState.changed += surface.redraw
                MapState.changed += onMapState
                NavEngine.changed += surface.redraw
                gr.gamemap.Convoy.changed += surface.redraw
                gr.gamemap.Convoy.changed += onRace
                gr.gamemap.InvitePop.changed += surface.redraw
                gr.gamemap.LocationStatus.changed += surface.redraw
                gr.gamemap.Convoy.proposed += onProposal
                NavEngine.changed += onNav
                Voice.changed += onNav
                gr.gamemap.Alerts.changed += onAlerts
                gr.gamemap.Friends.changed += onFriends
                gr.gamemap.Friends.setInCar(true)
                gr.gamemap.LocationStatus.onCar(true)
                onFriends()
                ensureLocation()
                tryResume(0)
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
                gr.gamemap.Convoy.changed -= onRace
                gr.gamemap.InvitePop.changed -= surface.redraw
                gr.gamemap.LocationStatus.changed -= surface.redraw
                gr.gamemap.Convoy.proposed -= onProposal
                TileCache.loaded -= surface.redraw
                LocationTracker.changed -= surface.redraw
                MapState.changed -= surface.redraw
                MapState.changed -= onMapState
                NavEngine.changed -= surface.redraw
                NavEngine.changed -= onNav
                Voice.changed -= onNav
                gr.gamemap.Alerts.changed -= onAlerts
                gr.gamemap.Friends.changed -= onFriends
                gr.gamemap.Friends.setInCar(false)
                gr.gamemap.LocationStatus.onCar(false)
                if (navigating) nav.navigationEnded()
                LocationTracker.release(carContext, "car")
            }
        })
    }

    /** The route that ran when the car app last closed: started again (once there's a GPS fix). */
    private fun tryResume(attempt: Int) {
        if (NavEngine.active || NavEngine.planning || attempt > 10) return
        val (place, via) = NavEngine.resumable(carContext) ?: return
        val here = LocationTracker.position ?: run { main.postDelayed({ tryResume(attempt + 1) }, 2000); return }
        if (gr.gamemap.Geo.distance(here, place.goal) < 150) return // already there
        NavEngine.resume(carContext, place, via)
        CarToast.makeText(carContext, gr.gamemap.L.t("Route to ${place.name} resumed"), CarToast.LENGTH_LONG).show()
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
        if (navigating) syncTrip()
    }

    private var tripSentAt = 0L
    private var tripFailed = false

    /**
     * The next turn and the time left, sent to the car itself: cars that support it show them on the
     * display behind the wheel and the head-up display, and Android Auto shows a turn pop-up while
     * another app (music) is on the screen. Once a second at most.
     */
    private fun syncTrip() {
        if (!NavEngine.active || NavEngine.arrived || tripFailed) return
        val now = System.currentTimeMillis()
        if (now - tripSentAt < 1000) return
        tripSentAt = now
        val step = NavEngine.currentStep ?: return
        try {
            val zone = java.util.TimeZone.getDefault()
            fun est(m: Double, secs: Double) = TravelEstimate.Builder(distance(m), DateTimeWithZone.create(now + (secs * 1000).toLong(), zone))
                .setRemainingTimeSeconds(secs.toLong().coerceAtLeast(0)).build()
            val toStep = NavEngine.distanceToNext / maxOf(LocationTracker.speedMps.toDouble(), 8.0)
            val b = Trip.Builder()
                .addStep(toCarStep(step, false), est(NavEngine.distanceToNext, toStep))
                .addDestination(Destination.Builder().setName(NavEngine.destinationName.ifBlank { gr.gamemap.L.t("Destination") }).build(),
                    est(NavEngine.remainingDistance, NavEngine.remainingSeconds))
            NavEngine.currentRoad().takeIf { it.isNotBlank() }?.let { b.setCurrentRoad(it) }
            nav.updateTrip(b.build())
        } catch (e: Exception) {
            tripFailed = true // an older car host: the map's own turn box is enough
            gr.gamemap.CrashLog.note("car trip info: $e")
        }
    }

    /** For tests: what the car is told about the trip right now. */
    internal fun tripForTest(): Trip? {
        val step = NavEngine.currentStep ?: return null
        val now = System.currentTimeMillis(); val zone = java.util.TimeZone.getDefault()
        fun est(m: Double, secs: Double) = TravelEstimate.Builder(distance(m), DateTimeWithZone.create(now + (secs * 1000).toLong(), zone)).setRemainingTimeSeconds(secs.toLong().coerceAtLeast(0)).build()
        val b = Trip.Builder().addStep(toCarStep(step, false), est(NavEngine.distanceToNext, 10.0))
            .addDestination(Destination.Builder().setName(NavEngine.destinationName).build(), est(NavEngine.remainingDistance, NavEngine.remainingSeconds))
        NavEngine.currentRoad().takeIf { it.isNotBlank() }?.let { b.setCurrentRoad(it) }
        return b.build()
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
            CarToast.makeText(carContext, gr.gamemap.L.t("Faster route · saves ${(o.savingSeconds + 30) / 60} min") +
                if (NavEngine.autoSwitch) " · " + gr.gamemap.L.t("Switch") else "", CarToast.LENGTH_LONG).show()
            return
        }
        val via = o.option.via.takeIf { it.isNotBlank() }?.let { "Via $it" } ?: "Avoids traffic on your route"
        val alert = Alert.Builder(OFFER_ALERT, CarText.create(gr.gamemap.L.t("Faster route · saves ${(o.savingSeconds + 30) / 60} min")), NavEngine.OFFER_SECONDS * 1000L)
            .setSubtitle(CarText.create(gr.gamemap.L.t(via)))
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

    /** Android Auto's ☰ in the style's colours (cars that don't allow custom colours show their own). */
    private fun styledMenu(): Action {
        val th = MapState.carTheme ?: MapState.theme
        val light = th.lookFor(false); val dark = th.lookFor(true)
        fun solid(c: Int) = android.graphics.Color.rgb(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
        val ink = androidx.car.app.model.CarColor.createCustom(solid(light.route), solid(dark.route))
        val bg = androidx.car.app.model.CarColor.createCustom(solid(light.hudPanel), solid(dark.hudPanel))
        val ic = CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_menu)).setTint(ink).build()
        return try {
            Action.Builder().setIcon(ic).setBackgroundColor(bg).setOnClickListener { screenManager.push(CarMenuScreen(carContext)) }.build()
        } catch (e: Exception) {
            Action.Builder().setIcon(ic).setOnClickListener { screenManager.push(CarMenuScreen(carContext)) }.build()
        }
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

    /** A button on the Friends panel drawn on the map. */
    private fun onFriendsPanel(what: String) {
        val C = gr.gamemap.Convoy; val F = gr.gamemap.Friends; val L = gr.gamemap.L
        when {
            what == "back" -> surface.friendsBack()
            what == "convoy" -> surface.openFriends("convoy")
            what == "more" -> screenManager.push(FriendsCarScreen(carContext))
            what == "msg" -> { surface.openFriends(null); openQuickPanel() }
            what == "leave" -> { surface.openFriends(null); openLeavePanel() }
            what == "drive" -> C.destination?.let { surface.openFriends(null); NavEngine.start(carContext, it); MapState.car.recenter() }
            what == "start" -> {
                if (C.myName.isBlank()) C.myName = "Driver"
                val dest = if (NavEngine.active && !NavEngine.arrived) NavEngine.destination?.let { Place(NavEngine.destinationName, "", it) } else NavEngine.planPlace
                C.create(dest)
                surface.openFriends(null)
                if (dest == null) openConvoyWhere() else openConvoyHow()
            }
            what.startsWith("inv:") -> {
                val f = if (F.ready && F.core.started) F.core.friends.firstOrNull { it.fid == what.removePrefix("inv:") } else null
                if (f != null) {
                    if (!C.active) {
                        if (C.myName.isBlank()) C.myName = "Driver"
                        val dest = if (NavEngine.active && !NavEngine.arrived) NavEngine.destination?.let { Place(NavEngine.destinationName, "", it) } else NavEngine.planPlace
                        C.create(dest)
                    }
                    F.invite(f)
                    CarToast.makeText(carContext, L.t("Invite sent to ${f.name}"), CarToast.LENGTH_SHORT).show()
                }
            }
            what.startsWith("join:") -> { surface.openFriends(null); carJoinConvoy(carContext, what.removePrefix("join:")) }
            what.startsWith("member:") -> {
                val m = C.members[what.removePrefix("member:")]
                val at = m?.shown ?: m?.pos
                surface.openFriends(null)
                if (at != null) MapState.car.pan(at, 0f)
            }
        }
    }

    /** A GameMap button drawn on the car map was tapped. */
    private fun onCarButton(id: String) {
        if (id.startsWith("route:")) { NavEngine.select(id.removePrefix("route:").toIntOrNull() ?: return); surface.redraw(); return }
        if (id == "routestart") { NavEngine.startPlanned(); surface.redraw(); return }
        if (id == "routecancel") { NavEngine.cancelPlan(); surface.redraw(); return }
        if (id.startsWith("park:")) {
            val pick = gr.gamemap.CarParking.list?.getOrNull(id.removePrefix("park:").toIntOrNull() ?: -1) ?: return
            gr.gamemap.CarParking.list = null
            NavEngine.stop(); NavEngine.dismissTrip()
            NavEngine.start(carContext, pick) // straight there: it's around the corner
            surface.redraw()
            return
        }
        if (id == "convoy:accept") { gr.gamemap.Convoy.accept(); return }
        // the invite card: Join (straight in, then Drive to on the convoy's page) or Not now
        if (id == "inv:no") { gr.gamemap.InvitePop.notNow(); surface.redraw(); return }
        if (id == "inv:join") { gr.gamemap.InvitePop.take()?.let { carJoinConvoy(carContext, it.convoy) }; surface.redraw(); return }
        if (id == "convoy:how") { openConvoyHow(); return }
        if (id == "convoy:go") {
            // the lobby's big button: START (host, or nobody else at the start), READY UP, or take ready back
            val C = gr.gamemap.Convoy
            val dest = C.destination ?: return
            when {
                C.lobby().isEmpty() -> { NavEngine.start(carContext, dest); MapState.car.recenter() }
                C.meReady -> C.setReady(false)
                else -> C.setReady(true)
            }
            return
        }
        if (id == "convoy:decline") { gr.gamemap.Convoy.decline(); return }
        if (id.startsWith("fp:")) { onFriendsPanel(id.removePrefix("fp:")); backFriends.isEnabled = surface.friendsOpen; surface.redraw(); return }
        if (id.startsWith("cp:")) { onPanel(id.removePrefix("cp:")); surface.redraw(); return }
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
            "report" -> openReportPanel()
            // Friends drawn on the map in the style (the convoy first when in one)
            "friends" -> { surface.openFriends(if (gr.gamemap.Convoy.active) "convoy" else "list"); backFriends.isEnabled = true }
            "zoomin" -> MapState.car.zoomBy(1.0)
            "zoomout" -> MapState.car.zoomBy(-1.0)
            "recenter" -> MapState.car.recenter()
            "overview" -> NavEngine.route.takeIf { it.size > 1 }?.let { r -> MapState.car.showOverview(r.filterIndexed { i, _ -> i % 5 == 0 } + r.last()) }
            "end" -> NavEngine.stop()
            "menu" -> openMenuPanel()
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
        if (surface.ownButtons && NavEngine.planning) {
            // choosing a route: the cards are on the map; these are for the knob / touchpad
            // (and anyone who'd rather): Start, the next route, close
            val strip = ActionStrip.Builder()
                .addAction(Action.Builder().setTitle(gr.gamemap.L.t("Start")).setOnClickListener { NavEngine.startPlanned() }.build())
            // (Android Auto allows one titled action here: the others are icons)
            if (NavEngine.options.size > 1) strip.addAction(action(R.drawable.ic_next) {
                NavEngine.select((NavEngine.selected + 1) % NavEngine.options.size); surface.redraw()
            })
            strip.addAction(action(R.drawable.ic_i_close) { NavEngine.cancelPlan() })
            return NavigationTemplate.Builder().setActionStrip(strip.build())
                .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build()).build()
        }
        if (surface.ownButtons) {
            // GameMap draws its own see-through buttons; Android Auto keeps one: the ☰ menu, in the
            // style's colours (its panel behind, the route colour for the lines) where the car allows
            val b = NavigationTemplate.Builder()
            try { b.setActionStrip(ActionStrip.Builder().addAction(styledMenu()).build()) }
            catch (e: Exception) { b.setActionStrip(ActionStrip.Builder().addAction(action(R.drawable.ic_menu) { screenManager.push(CarMenuScreen(carContext)) }).build()) }
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
        if (s.road.isNotBlank()) b.setRoad(gr.gamemap.Names.shown(s.road))
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
            if (dir == "uturn") { gr.gamemap.drawUTurn(c, x, y, s, p); return@forEachIndexed }
            val ang = when {
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
        const val FRIEND_ALERT = 9
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
        val anyToll = opts.any { it.tollCost > 0 || it.hasToll }
        opts.forEachIndexed { i, o ->
            // title: the time (with traffic) and the toll price, so the price is never cut off
            val toll = when {
                o.tollCost > 0 -> gr.gamemap.Tolls.money(o.tollCost)
                o.hasToll -> gr.gamemap.L.t("tolls")
                anyToll -> gr.gamemap.L.t("No tolls")
                else -> null
            }
            val title = SpannableString(" " + (toll?.let { " · $it" } ?: ""))
            title.setSpan(DurationSpan.create(o.seconds.toLong()), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val line = SpannableString(" · " + gr.gamemap.L.t(if (o.via.isNotBlank()) "via ${o.via}" else "route ${i + 1}"))
            line.setSpan(DistanceSpan.create(carDistance(o.distance)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            val row = Row.Builder().setTitle(title).addText(line)
            val notes = listOfNotNull(
                if (o.trafficSeconds != null && i == fastest && opts.size > 1) gr.gamemap.L.t("Fastest") else null,
                if (o.delaySeconds >= 60) gr.gamemap.L.t("+${o.delaySeconds / 60} min traffic") else null,
                if (o.tolls.size > 1) gr.gamemap.L.t("${o.tolls.size} toll stations") else null,
            )
            if (notes.isNotEmpty()) row.addText(notes.joinToString(" · "))
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

/** Car searches run here: several at once, so a new search never waits behind an old one. */
private val carWork = Executors.newCachedThreadPool(gr.gamemap.Bg.factory("car"))

class SearchScreen(carContext: CarContext, private val onPick: ((Place) -> Unit)? = null) : Screen(carContext) {
    private var results: List<Place> = carStart()
    /** [results] are the saved/recent places shown before typing (not search results). */
    private var showingStart = true
    private var loading = false
    private var message = gr.gamemap.L.t("Type a destination")
    private var typed = ""
    @Volatile private var seq = 0
    private val main = Handler(Looper.getMainLooper())
    private val debounce = Runnable { search(typed, full = false) }

    init {
        LocationTracker.position?.let { Places.warmUp(it, force = true) }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { main.removeCallbacks(debounce); seq++ }
        })
    }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                typed = searchText
                main.removeCallbacks(debounce)
                if (searchText.trim().length < 2) {
                    seq++
                    results = carStart(); showingStart = true; loading = false
                    message = gr.gamemap.L.t("Type a destination")
                    invalidate()
                } else main.postDelayed(debounce, 350)
            }

            override fun onSearchSubmitted(searchText: String) {
                typed = searchText
                main.removeCallbacks(debounce)
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
            // the place itself isn't on the map: say so, so the first match isn't taken for it
            val notHere = if (!showingStart && typed.trim().length >= 2) Places.missingWord(typed, results) else null
            if (notHere != null) list.addItem(Row.Builder().setTitle(gr.gamemap.L.t("No “$notHere” on the map here"))
                .addText(gr.gamemap.L.t("Other matches below. Add it on your phone: Can't find it?")).build())
            results.take(if (notHere != null) 5 else 6).forEach { p ->
                val row = Row.Builder().setTitle(p.name.ifBlank { p.address.ifBlank { "📍" } }).setOnClickListener {
                    screenManager.pop()
                    onPick?.invoke(p) ?: NavEngine.plan(carContext, p)
                }
                val dist = near?.let { NavEngine.formatDistance(gr.gamemap.Geo.distance(it, p.pos)) }
                val line = listOfNotNull(dist, p.address.takeIf { it.isNotBlank() && it != p.name }?.take(70)).joinToString(" · ")
                if (line.isNotBlank()) row.addText(line)
                list.addItem(row.build())
            }
            b.setItemList(list.build())
        }
        return b.build()
    }

    /** Like the phone: the names on the phone at once, then the search services merged in. */
    private fun search(q: String, full: Boolean) {
        if (q.trim().length < 2) return
        val my = ++seq
        loading = true
        if (showingStart) { results = emptyList(); showingStart = false }
        invalidate()
        val near = LocationTracker.position
        carWork.execute {
            if (my != seq) return@execute // typed on meanwhile: skip
            val local = try { Places.local(q, near) } catch (e: Exception) { emptyList() }
            if (local.isNotEmpty()) main.post { if (my == seq) { results = local; invalidate() } }
            if (my != seq) return@execute
            val r = try { Places.find(q, near, full, local) } catch (e: Exception) { null }
            main.post {
                if (my != seq) return@post
                loading = false
                results = if (r.isNullOrEmpty()) local else r
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
                    CarToast.makeText(carContext, gr.gamemap.L.t(if (ok) "Thanks! ${t.title} reported" else "Couldn't send the report"), CarToast.LENGTH_SHORT).show()
                }
                screenManager.pop()
            }.build())
        }
        return ListTemplate.Builder().setTitle(gr.gamemap.L.t("Report")).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/** Joins a friend's convoy from the car (no name yet: "Driver", changeable on the phone). */
fun carJoinConvoy(ctx: CarContext, convoy: String) {
    val C = gr.gamemap.Convoy
    if (C.myName.isBlank()) C.myName = "Driver"
    val ok = C.join(convoy)
    CarToast.makeText(ctx, gr.gamemap.L.t(if (ok) "You joined the convoy" else "That convoy has ended"), CarToast.LENGTH_SHORT).show()
}

/** How many rows a list may show in this car (6 if the car doesn't say). */
fun carListLimit(ctx: CarContext): Int = try {
    if (ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_2)
        ctx.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
            .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST).coerceIn(3, 12)
    else 6
} catch (e: Exception) { 6 }

/** Friends in the car: invites to join first, then friends (online first) to invite to my convoy. */
class FriendsCarScreen(carContext: CarContext) : Screen(carContext) {
    private val main = Handler(Looper.getMainLooper())
    private var lastInvalidate = 0L
    private val refresh = Runnable { lastInvalidate = System.currentTimeMillis(); invalidate() }
    private val listener: () -> Unit = {
        main.removeCallbacks(refresh)
        val wait = 2000 - (System.currentTimeMillis() - lastInvalidate)
        if (wait <= 0) refresh.run() else main.postDelayed(refresh, wait)
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                gr.gamemap.Friends.changed += listener
                gr.gamemap.Convoy.changed += listener
                gr.gamemap.Friends.kick()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                gr.gamemap.Friends.changed -= listener
                gr.gamemap.Convoy.changed -= listener
                main.removeCallbacks(refresh)
            }
        })
    }

    /** Buttons on rows broke once (the car wants an icon on each): never again a crash, plain rows instead. */
    private var plainRows = false

    override fun onGetTemplate(): Template = try { build() } catch (e: Exception) {
        gr.gamemap.CrashLog.note("car friends: $e")
        if (plainRows) throw e
        plainRows = true
        build()
    }

    private fun build(): Template {
        val F = gr.gamemap.Friends
        val C = gr.gamemap.Convoy
        val L = gr.gamemap.L
        val limit = carListLimit(carContext)
        val canAct = carContext.carAppApiLevel >= 6 && !plainRows // a button on each row (older cars: tap the row)
        val sections = ArrayList<Pair<String, List<Row>>>()
        var used = 0
        fun section(title: String, rows: List<Row>) {
            val room = limit - used
            if (rows.isEmpty() || room <= 0) return
            val take = rows.take(room); used += take.size
            sections += title to take
        }
        fun row(title: String, text: String, button: String?, onClick: () -> Unit): Row {
            val r = Row.Builder().setTitle(title).addText(text)
            // Android Auto wants an icon on a row's button (a title alone throws)
            val res = when (button) { L.t("Join") -> R.drawable.ic_act_convoy; else -> R.drawable.ic_i_plus }
            val acted = button != null && canAct && try {
                r.addAction(Action.Builder().setTitle(button).setIcon(icon(carContext, res)).setOnClickListener(onClick).build()); true
            } catch (e: Exception) { gr.gamemap.CrashLog.note("car friends row button: $e"); false }
            if (!acted) r.setOnClickListener(onClick)
            return r.build()
        }
        fun invite(f: gr.gamemap.FriendCore.Friend) {
            if (!C.active) {
                if (C.myName.isBlank()) C.myName = "Driver"
                val dest = if (NavEngine.active && !NavEngine.arrived) NavEngine.destination?.let { Place(NavEngine.destinationName, "", it) } else NavEngine.planPlace
                C.create(dest)
            }
            F.invite(f)
            CarToast.makeText(carContext, L.t("Invite sent to ${f.name}"), CarToast.LENGTH_SHORT).show()
            listener()
        }
        if (F.ready && F.core.started) {
            val c = F.core
            // invites to me first: Join
            section(L.t("Invites"), c.invites.filter { it.convoy != C.code }.map { i ->
                val mins = ((System.currentTimeMillis() - i.at) / 60_000).coerceAtLeast(0)
                row(L.t("${i.from} invites you"), (if (i.dest.isNotBlank()) L.t("Convoy to ${i.dest}") else L.t("No destination yet")) + " · " + L.t("$mins min ago"), L.t("Join")) {
                    screenManager.pop(); carJoinConvoy(carContext, i.convoy)
                }
            })
            if (C.active) {
                // in a convoy: who's in, who has my invite, then everyone else
                // the convoy itself: where it's going (one tap to drive there) and leaving it
                val dest = C.destination
                val top = ArrayList<Row>()
                if (dest != null && !NavEngine.active) top += Row.Builder().setTitle(L.t("Drive to ${dest.name}")).addText(L.t("The convoy's destination"))
                    .setOnClickListener { screenManager.popToRoot(); NavEngine.start(carContext, dest) }.build()
                top += Row.Builder().setTitle(L.t("Leave the convoy")).addText(L.t("The others carry on without you"))
                    .setOnClickListener { screenManager.push(LeaveConvoyCarScreen(carContext)) }.build()
                section(L.t("Convoy") + (C.code?.let { " · " + it.uppercase() } ?: ""), top)
                val inConvoy = C.members.values.filter { it.state != "left" && !it.sharing }
                section(L.t("In your convoy"), inConvoy.map { m ->
                    val st = when { m.stale -> L.t("no signal"); m.lostSecs > 0 -> L.t(C.lostText(m.lostSecs)); m.state == "ready" -> L.t(if (m.ready) "ready ✓" else "joined"); m.state == "arrived" -> L.t("arrived"); else -> L.t("${m.kmh} km/h") }
                    // tap: the map goes to them
                    Row.Builder().setTitle("● " + m.name).addText(st + " · " + L.t("tap to see on the map")).setOnClickListener {
                        val at = m.shown ?: m.pos
                        screenManager.popToRoot()
                        if (at != null) MapState.car.pan(at, 0f)
                    }.build()
                })
                val members = inConvoy.map { it.fid }.toSet()
                val sent = c.friends.filter { it.fid !in members && C.code?.let { x -> c.sentTo(it.fid, x) } != null }
                section(L.t("Invited"), sent.map { f ->
                    val st = C.code?.let { c.sentTo(f.fid, it)?.status }
                    row((if (c.isOnline(f.fid)) "● " else "") + c.shownName(f), L.t(when (st) { "seen" -> "✓✓ Seen"; "no" -> "Not now"; else -> "✓ Sent" }), L.t("Again")) { invite(f) }
                })
                val rest = c.friends.filter { it.fid !in members && it !in sent }.sortedWith(compareBy({ !c.isOnline(it.fid) }, { it.name.lowercase() }))
                section(L.t("Friends"), rest.map { f -> friendRow(c, f, ::row) { invite(f) } })
            } else {
                val fr = c.friends.sortedWith(compareBy({ !c.isOnline(it.fid) }, { it.name.lowercase() }))
                section(L.t("Online now"), fr.filter { c.isOnline(it.fid) }.map { f -> friendRow(c, f, ::row) { invite(f) } })
                section(L.t("Not online"), fr.filter { !c.isOnline(it.fid) }.map { f -> friendRow(c, f, ::row) { invite(f) } })
            }
        }
        val online = if (F.ready && F.core.started) F.core.friends.count { F.core.isOnline(it.fid) } else 0
        val title = if (C.active) L.t("Friends · in your convoy") else L.t("Friends") + if (online > 0) " · " + L.t("$online online") else ""
        val b = ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK)
        if (sections.isEmpty()) {
            b.setSingleList(ItemList.Builder().setNoItemsMessage(L.t("No friends yet") + ". " + L.t("Add friends on your phone: You › Friends")).build())
        } else for ((t, rows) in sections) {
            val il = ItemList.Builder(); rows.forEach { il.addItem(it) }
            b.addSectionedList(androidx.car.app.model.SectionedItemList.create(il.build(), t))
        }
        // the header button: start a convoy (to where I'm going), or a quick message once in one
        val strip = ActionStrip.Builder()
        if (!C.active) strip.addAction(Action.Builder().setTitle(L.t("Start a convoy")).setOnClickListener {
            if (C.myName.isBlank()) C.myName = "Driver"
            val dest = if (NavEngine.active && !NavEngine.arrived) NavEngine.destination?.let { Place(NavEngine.destinationName, "", it) } else NavEngine.planPlace
            C.create(dest)
            CarToast.makeText(carContext, L.t("Convoy started: invite friends below"), CarToast.LENGTH_SHORT).show()
            listener()
        }.build())
        else {
            strip.addAction(Action.Builder().setTitle(L.t("Message")).setOnClickListener { screenManager.push(QuickMessageCarScreen(carContext)) }.build())
            strip.addAction(Action.Builder().setIcon(icon(carContext, R.drawable.ic_i_leave)).setOnClickListener { screenManager.push(LeaveConvoyCarScreen(carContext)) }.build())
        }
        b.setActionStrip(strip.build())
        return b.build()
    }

    private fun friendRow(c: gr.gamemap.FriendCore, f: gr.gamemap.FriendCore.Friend, row: (String, String, String?, () -> Unit) -> Row, onInvite: () -> Unit): Row {
        val L = gr.gamemap.L
        val on = c.isOnline(f.fid)
        val times = c.drivesWith(f.fid)
        // what they're doing: driving, in a convoy, online, or when they were last on
        val text = gr.gamemap.Friends.activityText(f.fid) + if (on && times > 0 && !c.isBusy(f.fid)) " · " + L.t("drove together $times times") else ""
        return row((if (on) "● " else "") + c.shownName(f), text, L.t("Invite"), onInvite)
    }
}

/** "Leave the convoy?" Leave / Stay: one tap from Friends, never by accident. */
class LeaveConvoyCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val L = gr.gamemap.L
        return androidx.car.app.model.MessageTemplate.Builder(L.t("The others carry on without you. You can join again from an invite."))
            .setTitle(L.t("Leave the convoy?")).setHeaderAction(Action.BACK)
            .addAction(Action.Builder().setTitle(L.t("Leave")).setBackgroundColor(CarColor.RED).setOnClickListener {
                gr.gamemap.Convoy.leave()
                CarToast.makeText(carContext, L.t("You left the convoy"), CarToast.LENGTH_SHORT).show()
                screenManager.popToRoot()
            }.build())
            .addAction(Action.Builder().setTitle(L.t("Stay")).setOnClickListener { screenManager.pop() }.build())
            .build()
    }
}

/** Quick messages to the convoy from the car: one tap, no typing; everyone sees and hears it. */
class QuickMessageCarScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val C = gr.gamemap.Convoy
        val list = ItemList.Builder()
        for ((k, _, _) in C.quick) list.addItem(Row.Builder().setTitle(C.messageText(k)).setOnClickListener {
            C.send(k)
            CarToast.makeText(carContext, gr.gamemap.L.t("Sent to the convoy"), CarToast.LENGTH_SHORT).show()
            screenManager.popToRoot()
        }.build())
        return ListTemplate.Builder().setTitle(gr.gamemap.L.t("Message the convoy")).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}


/** The ☰ menu: everything the map buttons do, as a list (also for cars that don't report taps). */
class CarMenuScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val rows = ArrayList<Row>()
        fun row(title: String, text: String? = null, onClick: () -> Unit) {
            val r = Row.Builder().setTitle(title).setOnClickListener(onClick)
            if (text != null) r.addText(text)
            rows += r.build()
        }
        fun friendsRow() {
            val F = gr.gamemap.Friends
            if (!F.ready || !F.core.started) return
            val inv = F.core.invites.firstOrNull { it.convoy != gr.gamemap.Convoy.code }
            val online = F.core.friends.count { F.core.isOnline(it.fid) }
            val text = when {
                inv != null -> gr.gamemap.L.t("${inv.from} invites you to a convoy")
                online > 0 -> gr.gamemap.L.t("$online friends online")
                F.core.friends.isNotEmpty() -> gr.gamemap.L.t("Invite friends")
                else -> gr.gamemap.L.t("No friends yet")
            }
            row((if (inv != null) "🔔 " else "👥 ") + gr.gamemap.L.t("Friends"), text) { screenManager.pop(); screenManager.push(FriendsCarScreen(carContext)) }
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
            // (Recenter is on the map itself)
            val cd = gr.gamemap.Convoy.destination
            val lobby = gr.gamemap.Convoy.lobby()
            if (gr.gamemap.Convoy.active && !NavEngine.active && cd != null && lobby.isNotEmpty()) {
                // the race start: ready here too; the countdown shows on the map
                val waiting = lobby.filter { !it.ready }.map { it.name }
                if (gr.gamemap.Convoy.meReady) row(gr.gamemap.L.t("✓ Ready (tap to undo)"),
                    if (waiting.isEmpty()) gr.gamemap.L.t("Everyone's ready!") else gr.gamemap.L.t("Waiting for ") + waiting.joinToString(", ")) {
                    gr.gamemap.Convoy.setReady(false); invalidate()
                } else row(gr.gamemap.L.t("🏁 I'm ready"), cd.name) { gr.gamemap.Convoy.setReady(true); screenManager.pop() }
                if (!gr.gamemap.Convoy.counting) row(gr.gamemap.L.t("🚗 Drive along"), gr.gamemap.L.t("Same route, no race")) {
                    gr.gamemap.Convoy.setAlong(true); screenManager.pop(); NavEngine.start(carContext, cd)
                }
            } else if (gr.gamemap.Convoy.active && !NavEngine.active && cd != null) row(gr.gamemap.L.t("Convoy: go"), cd.name) {
                screenManager.pop(); NavEngine.start(carContext, cd)
            } else row(gr.gamemap.L.t("Fuel nearby"), if (NavEngine.active) gr.gamemap.L.t("Add a fuel stop") else gr.gamemap.L.t("Prices and ratings")) {
                screenManager.pop(); screenManager.push(NearbyScreen(carContext, gr.gamemap.L.t("Fuel nearby"), listOf("fuel", "charging_station")))
            }
        }
        friendsRow()
        if (gr.gamemap.Convoy.active && gr.gamemap.Convoy.members.isNotEmpty()) row("💬 " + gr.gamemap.L.t("Message the convoy"), gr.gamemap.L.t("Need fuel, Stopping soon…")) {
            screenManager.pop(); screenManager.push(QuickMessageCarScreen(carContext))
        }
        if (gr.gamemap.Convoy.active) row(gr.gamemap.L.t("Leave convoy"), gr.gamemap.L.t("${gr.gamemap.Convoy.members.size + 1} drivers")) { gr.gamemap.Convoy.leave(); screenManager.pop() }
        else row(gr.gamemap.L.t("Map style"), (MapState.carTheme ?: MapState.theme).title) { MapState.nextCarTheme(); invalidate() }
        val stop = NavEngine.stops.firstOrNull()
        if (stop != null && NavEngine.active) row(gr.gamemap.L.t("Remove stop"), stop.name) { NavEngine.removeStop(stop); screenManager.pop() }
        row(gr.gamemap.L.t("Spoken directions"), if (Voice.muted) gr.gamemap.L.t("Off") else gr.gamemap.L.t("On")) { Voice.muted = !Voice.muted; invalidate() }
        // the car decides how many rows fit (often 6): the least needed go last
        rows.take(carListLimit(carContext)).forEach { list.addItem(it) }
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
        else carWork.execute {
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
