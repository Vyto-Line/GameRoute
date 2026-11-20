package gr.gamemap

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import kotlin.math.abs

/** Phone screen: the game map with search, quick places, route preview, driving cards and sheets. */
class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var map: GameMapView
    private lateinit var topArea: LinearLayout
    private lateinit var searchCard: LinearLayout
    private lateinit var search: EditText
    private lateinit var searchIcon: TextView
    private lateinit var clearBtn: TextView
    private lateinit var menuBtn: TextView
    /** Friends, in the search bar: red number = requests and invites waiting, green dot = a friend online. */
    private lateinit var peopleBtn: android.widget.FrameLayout
    private lateinit var peopleIcon: TextView
    private lateinit var peopleBadge: TextView
    private lateinit var peopleDot: View
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var suggestScroll: ScrollView
    private lateinit var suggestList: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var laterPill: TextView
    private lateinit var youBtn: View
    /** While driving the buttons fade; a tap on the map brings them back until then. */
    private var controlsUntil = 0L
    private lateinit var styleBtn: TextView
    private lateinit var recenterBtn: TextView
    private lateinit var muteBtn: TextView
    private lateinit var overviewBtn: TextView
    private lateinit var navMenuBtn: TextView
    private lateinit var reportBtn: TextView
    private lateinit var askCard: LinearLayout
    private lateinit var askText: TextView
    private var askShown: Alerts.Report? = null
    private lateinit var homeSheet: LinearLayout
    private lateinit var homeTitle: TextView
    private lateinit var homeCards: LinearLayout
    /** The slim bar's words, shown when Saved & recent is tucked away (46b). */
    private lateinit var homeHandle: TextView
    /** Saved & recent pulled up (remembered); tucks itself away after you pick a place. */
    private var homeOpen: Boolean
        get() = prefs().getBoolean("homeOpen", false)
        set(v) { slideHome(v) }

    /**
     * The sheet is always laid out open; tucked away it slides down so only its top bar shows.
     * Sliding (not resizing) keeps the cards whole while it moves, and nothing re-lays out.
     */
    private var homeAnim: android.animation.ValueAnimator? = null
    private var homeDragging = false

    /** How far down the sheet slides when tucked away (0 when there's nothing to tuck). */
    private fun homeClosedOffset(): Float {
        if (homeSheet.height == 0 || homeTitle.visibility != View.VISIBLE) return 0f
        val header = homeTitle.parent as View
        return (homeSheet.height - header.bottom - homeSheet.paddingBottom + 6 * d).coerceAtLeast(0f)
    }

    /** 0 = tucked away, 1 = open: moves the sheet, cross-fades the bar's words and the title. */
    private fun setHomeFraction(f: Float) {
        val off = homeClosedOffset()
        homeSheet.translationY = off * (1f - f)
        homeHandle.alpha = (1f - f * 2.2f).coerceIn(0f, 1f)
        homeTitle.alpha = ((f - 0.25f) * 1.6f).coerceIn(0f, 1f)
        // tucked away the cards' tops would peek under the bar: they fade with the slide
        homeCards.alpha = (f * 1.8f).coerceIn(0f, 1f)
        exploreCard?.alpha = homeCards.alpha
        homeHandle.visibility = if (homeHandle.alpha > 0f) View.VISIBLE else View.INVISIBLE
        updateMapInsets()
    }

    private fun homeFraction(): Float {
        val off = homeClosedOffset()
        return if (off <= 0f) 1f else (1f - homeSheet.translationY / off).coerceIn(0f, 1f)
    }

    /** Glides from wherever the sheet is (open, tucked, or where the finger let go) to [open]. */
    private fun slideHome(open: Boolean, velocity: Float = 0f) {
        if (open != prefs().getBoolean("homeOpen", false)) prefs().edit().putBoolean("homeOpen", open).apply()
        homeAnim?.cancel()
        val from = homeFraction(); val to = if (open) 1f else 0f
        if (from == to || homeSheet.height == 0) { setHomeFraction(to); return }
        val dist = Math.abs(to - from) * homeClosedOffset()
        homeAnim = android.animation.ValueAnimator.ofFloat(from, to).apply {
            // a flick carries on at its own speed; otherwise ~260 ms for the whole way
            val ms = if (Math.abs(velocity) > 0.3f) (dist / Math.abs(velocity)).toLong().coerceIn(120L, 300L) else (260 * Math.abs(to - from)).toLong().coerceAtLeast(140L)
            duration = animMs(ms)
            interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener { setHomeFraction(it.animatedValue as Float) }
            start()
        }
    }

    // the finger on the sheet: it follows, between tucked away and open
    private var homeDragStartF = 0f
    private fun homeDragStart() { homeAnim?.cancel(); homeDragging = true; homeDragStartF = homeFraction() }
    private fun homeDragTo(dy: Float) {
        val off = homeClosedOffset()
        if (off > 0f) setHomeFraction((homeDragStartF - dy / off).coerceIn(0f, 1f))
    }
    private fun homeDragEnd(dy: Float, vy: Float) {
        homeDragging = false
        slideHome(when { vy < -0.5f -> true; vy > 0.5f -> false; else -> homeFraction() > 0.5f }, vy * 1f)
    }
    private lateinit var navCard: LinearLayout
    private lateinit var etaBig: TextView
    private lateinit var etaTraffic: TextView
    private lateinit var etaSmall: TextView
    private lateinit var endBtn: TextView
    private lateinit var shareBtn: TextView
    private lateinit var preview: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var previewSub: TextView
    private lateinit var previewList: LinearLayout
    private lateinit var previewClose: TextView
    private lateinit var previewSave: TextView
    private lateinit var previewBack: TextView
    private lateinit var trafficBtn: TextView
    private lateinit var laterBtn: TextView
    private lateinit var convoyNewBtn: TextView
    private lateinit var convoyBtn: TextView
    private lateinit var startBtn: TextView
    private lateinit var offerCard: LinearLayout
    private lateinit var offerTitle: TextView
    private lateinit var offerSub: TextView
    private lateinit var offerVia: TextView
    private lateinit var keepBtn: TextView
    private lateinit var switchBtn: TextView
    private lateinit var sheetOverlay: FrameLayout
    private lateinit var tripOverlay: FrameLayout
    private lateinit var placeCard: LinearLayout
    /** The place (icon tapped or pin dropped) whose card is showing. */
    private var placeShown: Place? = null
    private var placeEta: String? = null
    private lateinit var tripCard: LinearLayout
    private var shownTrip: Explorer.Trip? = null
    private lateinit var sheetHost: LinearLayout
    private var sheetKind = ""
    private val fabs = mutableListOf<TextView>()

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var d = 1f
    private var insets = Rect()
    private var querySeq = 0
    private var lastResults: List<Place> = emptyList()
    /** "home" / "work" while the user is choosing that saved place in search. */
    private var pickFor: String? = null
    private var etaAskedAt = 0L
    private var etaVersion = 0
    private var lastHomeKey = ""
    private var lastPreviewKey = ""

    private val stateListener: () -> Unit = { refreshChrome() }
    private val ticker = object : Runnable {
        override fun run() {
            if (NavEngine.offer != null) { refreshChrome(); main.postDelayed(this, 1000) }
        }
    }

    private lateinit var welcome: FrameLayout
    private var welcomePage = 0
    private var wantedPermissions = arrayOf<String>()

    /** The three first-launch screens: what GameRoute is, why location, pick a look. */
    internal fun showWelcome(page: Int = 0, quiet: Boolean = false) {
        wallAnim?.cancel(); wallAnim = null
        val prevPage = welcomePage
        val firstShow = welcome.visibility != View.VISIBLE || welcome.childCount == 0
        welcomePage = page
        val old = if (firstShow) null else welcome.getChildAt(welcome.childCount - 1)
        if (firstShow) welcome.removeAllViews()
        welcome.visibility = View.VISIBLE
        if (welcomeSwipe == null) {
            // swipe left / right between the pages
            welcomeSwipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent) = true
                override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                    if (kotlin.math.abs(vx) < 600 || kotlin.math.abs(vx) < kotlin.math.abs(vy)) return false
                    if (vx < 0 && welcomePage < 2) showWelcome(welcomePage + 1)
                    else if (vx > 0 && welcomePage > 0) showWelcome(welcomePage - 1)
                    return true
                }
            })
            welcome.setOnTouchListener { _, ev -> welcomeSwipe?.onTouchEvent(ev) ?: false }
        }
        val light = !MapState.dark
        val bgCol = if (light) Color.rgb(247, 250, 249) else Color.rgb(18, 22, 26)
        val inkCol = if (light) Color.rgb(23, 50, 45) else Color.rgb(236, 240, 239)
        val dimCol = if (light) Color.rgb(63, 95, 88) else Color.rgb(170, 184, 180)
        val acc = Color.rgb(84, 104, 245)
        welcome.setBackgroundColor(bgCol)
        val sysTop = (ViewCompat.getRootWindowInsets(welcome)?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: (24 * d).toInt())
        val sysBottom = (ViewCompat.getRootWindowInsets(welcome)?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0)
        fun thumb(th: Theme) = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val id = resources.getIdentifier("thumb_${th.name.lowercase()}_${if (light) "l" else "d"}", "drawable", packageName)
            if (id != 0) setImageResource(id) else setBackgroundColor(th.lookFor(!light).style.land)
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = bg(Color.WHITE, radius = 12f)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = FrameLayout(this)
        val pad = (26 * d).toInt()
        when (page) {
            0 -> top.addView(LinearLayout(this).apply {
                // a tilted wall of map styles
                orientation = LinearLayout.VERTICAL; rotation = -6f; scaleX = 1.15f; scaleY = 1.15f
                val ths = Theme.entries.toList()
                for (r in 0 until 3) addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (k in 0 until 3) addView(thumb(ths[(r * 3 + k) % ths.size]), LinearLayout.LayoutParams(0, (96 * d).toInt(), 1f).apply {
                        val m = (4 * d).toInt(); setMargins(m, m, m, m)
                    })
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            1 -> {
                top.addView(thumb(MapState.theme).apply { alpha = 0.9f; background = null; clipToOutline = false }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                top.addView(View(this).apply { background = bg(Color.argb(50, 84, 104, 245), radius = 60f, pill = true) },
                    FrameLayout.LayoutParams((96 * d).toInt(), (96 * d).toInt(), Gravity.CENTER))
                top.addView(View(this).apply { background = bg(acc, Color.WHITE, 4f, 20f, pill = true) },
                    FrameLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt(), Gravity.CENTER))
            }
            else -> top.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(pad - (4 * d).toInt(), 0, pad - (4 * d).toInt(), 0)
                addView(label("PICK YOUR LOOK", 13f, bold = true).apply { setTextColor(acc); letterSpacing = 0.08f; setPadding((4 * d).toInt(), 0, 0, (8 * d).toInt()) })
                val ths = Theme.entries.toList().take(6)
                for (r in 0 until 2) addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (k in 0 until 3) {
                        val th = ths[r * 3 + k]; val sel = th == MapState.theme
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            background = bg(if (light) Color.WHITE else Color.rgb(30, 36, 42), if (sel) acc else Color.argb(60, 128, 128, 128), if (sel) 3f else 1f, 14f)
                            val q = (3 * d).toInt(); setPadding(q, q, q, q)
                            addView(thumb(th), LinearLayout.LayoutParams(MATCH_PARENT, (78 * d).toInt()))
                            addView(label(th.title, 12f, bold = true).apply { setTextColor(inkCol); maxLines = 2; setPadding((4 * d).toInt(), (5 * d).toInt(), (2 * d).toInt(), (5 * d).toInt()) })
                            setOnClickListener { MapState.setTheme(th); welcome.removeAllViews(); welcome.visibility = View.GONE; showWelcome(2, quiet = true) }
                        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { val m = (4 * d).toInt(); setMargins(m, m, m, m) })
                    }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(label("${Theme.entries.size - 6} more in Map style. Light or dark follows the sun.", 13f).apply {
                    setTextColor(dimCol); gravity = Gravity.CENTER; setPadding(0, (10 * d).toInt(), 0, 0)
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
        }
        top.clipChildren = true
        col.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val (title, body) = when (page) {
            0 -> "Navigation that looks like your favourite games" to
                "Turn-by-turn directions, live traffic and camera alerts, on a map styled like an open-world game. On your phone and in Android Auto."
            1 -> "GameRoute needs your location" to
                "To guide you turn by turn and warn you about cameras, even with the screen off or in Android Auto. Your location stays on your phone; only routes and searches go to the map services."
            else -> "Pick your look" to "You can change it any time from the map, and give Android Auto a different one."
        }
        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, (22 * d).toInt(), pad, 0)
            addView(label(title, 27f, bold = true).apply { setTextColor(inkCol); typeface = MapState.theme.typeface })
            addView(label(body, 16f).apply { setTextColor(dimCol); setPadding(0, (10 * d).toInt(), 0, 0); setLineSpacing(0f, 1.15f) })
        }
        col.addView(textBlock)
        val main = pill(when (page) { 0 -> "Next"; 1 -> "Allow location"; else -> "Start exploring" }) {
            when (page) {
                0 -> showWelcome(1)
                1 -> { if (wantedPermissions.isNotEmpty()) permissions.launch(wantedPermissions); showWelcome(2) }
                else -> {
                    getSharedPreferences("gamemap", MODE_PRIVATE).edit().putBoolean("welcomeDone", true).apply()
                    welcome.animate().alpha(0f).setDuration(animMs(350)).withEndAction {
                        welcome.visibility = View.GONE; welcome.removeAllViews(); welcome.alpha = 1f
                    }.start()
                    refreshChrome()
                }
            }
        }.apply { setTextColor(Color.WHITE); textSize = 17f; background = bg(acc, radius = 27f, pill = true) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, (20 * d).toInt(), pad, (18 * d).toInt() + sysBottom)
            addView(main, LinearLayout.LayoutParams(MATCH_PARENT, (54 * d).toInt()))
            if (page == 1) addView(pill("Not now") { showWelcome(2) }.apply { setTextColor(acc); textSize = 15f })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, (14 * d).toInt(), 0, 0)
                for (k in 0 until 3) addView(View(context).apply { background = bg(if (k == page) acc else Color.argb(90, 128, 128, 128), radius = 4f, pill = true) },
                    LinearLayout.LayoutParams(((if (k == page) 22 else 8) * d).toInt(), (8 * d).toInt()).apply { marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt() })
            })
        })
        welcome.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = sysTop })
        if (!quiet) animateWelcome(old, col, top, textBlock, page, prevPage, firstShow)
    }

    private var wallAnim: android.animation.ObjectAnimator? = null

    private var welcomeSwipe: android.view.GestureDetector? = null

    /**
     * The welcome pages slide: the old page drifts out, the new one glides in, its picture a
     * little behind its text (parallax), and the words rise into place. The first page's wall of
     * map styles keeps floating gently.
     */
    private fun animateWelcome(old: View?, col: View, top: View, text: View, page: Int, prevPage: Int, firstShow: Boolean) {
        val w = (welcome.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        val ease = android.view.animation.DecelerateInterpolator(2.2f)
        val dir = if (page >= prevPage) 1f else -1f
        if (old != null) {
            old.animate().translationX(-dir * w * 0.45f).alpha(0f).setDuration(animMs(340)).setInterpolator(ease)
                .withEndAction { welcome.removeView(old) }.start()
            col.translationX = dir * w
            col.animate().translationX(0f).setDuration(animMs(420)).setInterpolator(ease).start()
            top.translationX = dir * w * 0.35f
            top.animate().translationX(0f).setDuration(animMs(620)).setInterpolator(ease).start()
        } else if (firstShow) {
            top.alpha = 0f; top.translationY = 60 * d
            top.animate().alpha(1f).translationY(0f).setDuration(animMs(700)).setInterpolator(ease).start()
        }
        text.alpha = 0f; text.translationY = 28 * d
        text.animate().alpha(1f).translationY(0f).setStartDelay(if (old != null) 160 else 260).setDuration(animMs(460)).setInterpolator(ease).start()
        if (page == 0) (top as? ViewGroup)?.getChildAt(0)?.let { wall ->
            wallAnim = android.animation.ObjectAnimator.ofFloat(wall, "translationX", -18 * d, 18 * d).apply {
                duration = 6000; repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocationTracker.start(this)
        LocationStatus.check()
    }

    // ---------------------------------------------------------------- location off / not allowed

    private lateinit var locCard: LinearLayout
    private val locListener: () -> Unit = { runOnUiThread { syncLocCard() } }
    private val dutyListener: () -> Unit = { runOnUiThread { dutyKey = "?"; moreKey = ""; refreshChrome(); map.invalidate() } }

    /** The card under Where to? when location is off or GameRoute isn't allowed to use it. */
    private fun syncLocCard() {
        if (!::locCard.isInitialized) return
        val st = LocationStatus.state
        if (st == LocationStatus.State.OK) { locCard.visibility = View.GONE; return }
        val off = st == LocationStatus.State.OFF
        val red = Color.rgb(255, 107, 107)
        val p = (12 * d).toInt()
        locCard.removeAllViews()
        locCard.background = bg(opaque(panel), red, 2f, 14f)
        locCard.setPadding(p, p, p, p)
        locCard.addView(TextView(this).apply { text = "⌖"; textSize = 24f; setTextColor(red); paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG },
            LinearLayout.LayoutParams((34 * d).toInt(), WRAP_CONTENT))
        locCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
            addView(label(L.t(if (off) "Location is off" else "GameRoute can't use your location"), 15.5f, bold = true).apply { setTextColor(ink) })
            addView(label(L.t(if (off) "GameRoute can't find you or guide you" else "Allow it so GameRoute can find you"), 13f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        locCard.addView(pill(L.t(if (off) "Turn on" else "Allow")) { fixLocation(off) }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 12f); textSize = 15f
            minHeight = (42 * d).toInt(); setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, (42 * d).toInt()))
        locCard.visibility = View.VISIBLE
    }

    private lateinit var dutyCard: LinearLayout
    private var dutyKey = ""

    /**
     * In the evening and at night (and on Sundays), when a pharmacy on duty is within 3 km and you're
     * not on a route: one slim bar under Where to? ("2 pharmacies on duty near you"). ✕ hides it till tomorrow.
     */
    private fun syncDutyCard() {
        if (!::dutyCard.isInitialized) return
        val now = Duties.now()
        val near = LocationTracker.position
        val prefs = getSharedPreferences("gamemap", MODE_PRIVATE)
        val today = run { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Europe/Athens") }.format(java.util.Date(now - 8 * 3_600_000L)) }
        val list = if (near == null || NavEngine.active || NavEngine.planningOn(false) || Convoy.active || !Duties.usuallyClosed(now) ||
            prefs.getString("dutyBarHidden", "") == today || LocationStatus.state != LocationStatus.State.OK) emptyList()
            else Duties.onDutyNear(near, 3000.0, now)
        val key = if (list.isEmpty()) "" else "${list.size}|${list[0].name}|${NavEngine.formatDistance(Geo.distance(near!!, list[0].pos))}|${theme.name}|${MapState.dark}"
        if (key == dutyKey) return
        dutyKey = key
        if (list.isEmpty()) { dutyCard.visibility = View.GONE; return }
        val p = (12 * d).toInt()
        val moon = readable(PoiLayer.MOON)
        dutyCard.removeAllViews()
        dutyCard.background = bg(opaque(panel), moon, 2f, 14f)
        dutyCard.setPadding(p, p * 3 / 4, p / 2, p * 3 / 4)
        dutyCard.isClickable = true
        dutyCard.setOnClickListener { showPharmacies() }
        dutyCard.addView(label("🌙", 22f), LinearLayout.LayoutParams((34 * d).toInt(), WRAP_CONTENT))
        dutyCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            addView(label(L.t(if (list.size == 1) "A pharmacy on duty near you" else "${list.size} pharmacies on duty near you"), 15f, bold = true).apply { setTextColor(ink) })
            addView(label(L.t("Nearest ") + NavEngine.formatDistance(Geo.distance(near!!, list[0].pos)) + " · " + Duties.till(list[0], now).orEmpty(), 13f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        dutyCard.addView(label(L.t("Show") + " ›", 15f, bold = true).apply { setTextColor(moon); setPadding(p / 2, p / 2, p / 2, p / 2) })
        dutyCard.addView(label("✕", 15f).apply {
            setTextColor(dim()); gravity = Gravity.CENTER; contentDescription = L.t("Hide till tomorrow"); isClickable = true
            setOnClickListener { prefs.edit().putString("dutyBarHidden", today).apply(); dutyKey = "x"; dutyCard.visibility = View.GONE }
        }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
        dutyCard.visibility = View.VISIBLE
    }
    internal fun dutyCardForTest(): View? = if (::dutyCard.isInitialized && dutyCard.visibility == View.VISIBLE) dutyCard else null

    private fun fixLocation(off: Boolean) {
        try {
            if (off) startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                getSharedPreferences("gamemap", MODE_PRIVATE).getInt("locAsked", 0) == 0) {
                getSharedPreferences("gamemap", MODE_PRIVATE).edit().putInt("locAsked", 1).apply()
                permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        } catch (e: Exception) { CrashLog.note("location settings: $e") }
    }

    /** For tests. */
    internal fun locCardForTest(): View? = if (::locCard.isInitialized && locCard.visibility == View.VISIBLE) locCard else null

    override fun onCreate(savedInstanceState: Bundle?) {
        val started = System.nanoTime()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        d = resources.displayMetrics.density

        map = GameMapView(this)
        map.onTouchStart = {
            if (search.hasFocus() || suggestScroll.visibility == View.VISIBLE || pickFor != null) closeSearch()
        }
        buildSearch()
        buildControls()
        buildHomeSheet()
        buildNavCard()
        buildPreview()
        buildOffer()
        sheetHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; elevation = 16 * d }
        sheetOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            elevation = 30 * d
            setBackgroundColor(Color.argb(110, 0, 0, 0))
            setOnClickListener { closeSheet() }
            addView(sheetHost, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }

        tripCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isClickable = true; elevation = 18 * d; gravity = Gravity.CENTER_HORIZONTAL }
        tripOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            elevation = 32 * d
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            isClickable = true
            addView(tripCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER).apply {
                val m = (22 * d).toInt(); setMargins(m, m, m, m)
            })
        }

        placeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true; elevation = 20 * d; visibility = View.GONE
        }
        map.onPlaceTap = { if (!NavEngine.planningOn(false)) showPlace(it, dropped = false) }
        map.onMapTap = {
            if (placeShown != null) closePlace()
            // driving: a tap brings the buttons back for a few seconds
            if (NavEngine.active) { controlsUntil = System.currentTimeMillis() + 5000; refreshChrome(); main.postDelayed({ refreshChrome() }, 5100) }
        }
        map.onLongPress = { p -> if (!NavEngine.planningOn(false)) dropPin(p) }
        map.onConvoyTap = { openSheet("convoy") }
        map.onFriendTap = { m -> if (!NavEngine.planningOn(false)) showFriend(m.id) }

        val root = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(homeSheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            // the convoy invite card: comes out from under Where to? (or the turn box) and goes back
            inviteClip = FrameLayout(this@MainActivity).apply { clipChildren = true; clipToPadding = true; visibility = View.GONE; elevation = 5 * d }
            addView(inviteClip, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(topArea, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(controls, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            // recenter: bottom right, just above whatever card is at the bottom (a target icon)
            addView(recenterBtn, FrameLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt(), Gravity.END or Gravity.BOTTOM).apply { marginEnd = (14 * d).toInt() })
            // map style: bottom right, under the thumb (the recenter button goes above it)
            addView(styleBtn, FrameLayout.LayoutParams((50 * d).toInt(), (50 * d).toInt(), Gravity.END or Gravity.BOTTOM).apply { marginEnd = (17 * d).toInt() })
            laterPill = TextView(this@MainActivity).apply {
                textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; visibility = View.GONE
                val q = (12 * d).toInt(); setPadding(q, 0, q, 0); elevation = 6 * d
                setOnClickListener { if (!Later.driving) showLater() }
            }
            addView(laterPill, FrameLayout.LayoutParams(WRAP_CONTENT, (38 * d).toInt(), Gravity.END or Gravity.BOTTOM).apply { marginEnd = (14 * d).toInt() })
            addView(navCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(offerCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(askCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(placeCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(sheetOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(tripOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            welcome = FrameLayout(context).apply { visibility = View.GONE; elevation = 40 * d; isClickable = true }
            addView(welcome, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // keep the search box from grabbing focus (and opening suggestions) at launch
        root.isFocusableInTouchMode = true
        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        setContentView(root)
        Startup.steps += "screen built" to (System.nanoTime() - started) / 1_000_000
        CrashLog.note("screen built in ${(System.nanoTime() - started) / 1_000_000} ms")
        root.requestFocus()
        root.viewTreeObserver.addOnPreDrawListener { L.translateTree(root); Icons.applyTree(root); true }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, wi ->
            val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = wi.getInsets(WindowInsetsCompat.Type.ime())
            insets.set(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            navBottom = bars.bottom
            val p = (10 * d).toInt()
            topArea.setPadding(bars.left + p, bars.top + p / 2, bars.right + p, 0)
            controls.setPadding(0, 0, bars.right + p, 0)
            navCard.setPadding(bars.left + 2 * p, p + p / 2, bars.right + 2 * p, bars.bottom + p + p / 2)
            preview.setPadding(bars.left + p + p / 2, p, bars.right + p + p / 2, bars.bottom + p + p / 2)
            homeSheet.setPadding(bars.left, p, bars.right, bars.bottom + p)
            placeCard.setPadding(bars.left + 2 * p, p + p / 2, bars.right + 2 * p, bars.bottom + 2 * p)
            // the keyboard up: the sheets sit on top of it (the app draws edge to edge, so Android doesn't move them)
            sheetOverlay.setPadding(0, if (ime.bottom > 0) bars.top else 0, 0, ime.bottom)
            sheetHost.setPadding(bars.left + 2 * p, p + p / 2, bars.right + 2 * p, (if (ime.bottom > 0) 0 else bars.bottom) + 2 * p)
            (offerCard.layoutParams as FrameLayout.LayoutParams).setMargins(bars.left + p, bars.top + p, bars.right + p, 0)
            (askCard.layoutParams as FrameLayout.LayoutParams).setMargins(bars.left + p, 0, bars.right + p, bars.bottom + (120 * d).toInt())
            offerCard.requestLayout()
            updateMapInsets()
            wi
        }
        topArea.viewTreeObserver.addOnGlobalLayoutListener { updateMapInsets() }
        preview.viewTreeObserver.addOnGlobalLayoutListener { updateMapInsets() }
        placeCard.viewTreeObserver.addOnGlobalLayoutListener { updateMapInsets() }
        homeSheet.viewTreeObserver.addOnGlobalLayoutListener { updateMapInsets() }

        handleLink(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    popups.isNotEmpty() -> popups.last().cancel()
                    welcome.visibility == View.VISIBLE -> if (welcomePage > 0) showWelcome(welcomePage - 1)
                    tripOverlay.visibility == View.VISIBLE -> NavEngine.dismissTrip()
                    placeShown != null -> closePlace()
                    askCard.visibility == View.VISIBLE -> Alerts.clearAsk()
                    sheetOverlay.visibility == View.VISIBLE -> sheetBack() // one step back (closes the first sheet)
                    suggestScroll.visibility == View.VISIBLE || pickFor != null -> closeSearch()
                    NavEngine.planningOn(false) -> NavEngine.cancelPlan()
                    NavEngine.active -> GameBuilder(this@MainActivity)
                        .setTitle("End navigation?")
                        .setPositiveButton("End") { _, _ -> NavEngine.stop() }
                        .setNegativeButton("Keep going", null)
                        .showT()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (LocationTracker.hasPermission(this)) LocationTracker.start(this)
        wantedPermissions = wanted.toTypedArray()
        // first launch: the welcome screens explain things and ask for location on their second page
        val underTest = Build.FINGERPRINT == "robolectric" // previews render the welcome screens on purpose
        if (!underTest && !getSharedPreferences("gamemap", MODE_PRIVATE).getBoolean("welcomeDone", false)) showWelcome()
        else permissions.launch(wantedPermissions)

        if (Updater.dueForCheck(this)) checkForUpdates(manual = false)
        CrashLog.note("phone app opened")
        CrashLog.pending()?.let { offerCrashReport(it) }
    }

    /** After a crash: offer to send the saved report (shared like any text; nothing is sent by itself). */
    private fun offerCrashReport(report: String) {
        GameBuilder(this@MainActivity)
            .setTitle("GameRoute closed unexpectedly")
            .setMessage("A report of what happened was saved on your phone. Send it so the problem can be fixed?")
            .setPositiveButton("Send report") { _, _ ->
                CrashLog.clear()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "GameRoute crash report")
                    .putExtra(Intent.EXTRA_TEXT, report)
                try { startActivity(Intent.createChooser(send, "Send the report with…")) } catch (_: Exception) {}
            }
            .setNegativeButton("Not now") { _, _ -> CrashLog.clear() }
            .showT()
    }

    // ------------------------------------------------------------------ look helpers

    private val theme get() = MapState.theme
    private val ink get() = Ui.ink(theme)
    private val panel: Int get() = Ui.panel(theme)
    private val accent get() = theme.route
    /** Secondary text (strength 255 = full ink); thin lines and fills below 120 stay faint. */
    private fun dim(a: Int = 185) = if (a < 120) Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink)) else Ui.dim(theme, a)
    private fun bg(fill: Int, stroke: Int = 0, strokeDp: Float = 0f, radius: Float = 16f, topOnly: Boolean = false, pill: Boolean = false) =
        HudDrawable(theme.hud, fill, d, radius, stroke, strokeDp, topOnly, pill)
    private fun luminance(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
    private fun onAccent(c: Int) = if (luminance(c) > 0.5) Color.rgb(15, 18, 22) else Color.WHITE
    private fun darken(c: Int) = Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10)
    private val lightPanel get() = luminance(panel) > 0.6
    private fun readable(c: Int) = Ui.onPanel(theme, c)

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        val p = (12 * d).toInt()
        setPadding(p + p / 2, p, p + p / 2, p)
        minHeight = (48 * d).toInt()
        setOnClickListener { onClick() }
    }

    /** The prestige ◆ before a title, in the rank's colour (Bronze, Silver…) and a little bigger. */
    private fun TextView.rankMark(): TextView {
        val t = text?.toString() ?: return this
        if (Xp.prestige <= 0 || !t.startsWith("◆")) return this
        text = android.text.SpannableString(t).apply {
            setSpan(android.text.style.ForegroundColorSpan(Xp.rankColor()), 0, 1, 0)
            setSpan(android.text.style.RelativeSizeSpan(1.2f), 0, 1, 0)
        }
        return this
    }

    private fun label(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    // ------------------------------------------------------------------ search & quick places

    private fun buildSearch() {
        val p = (12 * d).toInt()
        search = EditText(this).apply {
            hint = "Where to?"
            setSingleLine()
            textSize = 17f
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(p / 2, p, p / 2, p)
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { runQuery(text.toString(), full = true); true } else false
            }
            setOnFocusChangeListener { _, has -> if (has) showSuggestions(); refreshChrome() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    clearBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    val q = s?.toString().orEmpty()
                    main.removeCallbacksAndMessages(SEARCH_TOKEN)
                    if (q.trim().length < 2) { if (search.hasFocus()) showRecents() }
                    else main.postAtTime({ runQuery(q, full = false) }, SEARCH_TOKEN, android.os.SystemClock.uptimeMillis() + 550)
                }
            })
        }
        clearBtn = TextView(this).apply {
            text = "✕"; textSize = 18f; visibility = View.GONE
            setPadding(p, p, p, p)
            setOnClickListener { search.setText("") }
        }
        searchIcon = TextView(this).apply {
            text = "⌕"; textSize = 22f; gravity = Gravity.CENTER
            minWidth = (44 * d).toInt(); minHeight = (44 * d).toInt()
            setPadding(p / 2, 0, 0, 0)
            setOnClickListener { if (search.hasFocus() || suggestScroll.visibility == View.VISIBLE || pickFor != null) closeSearch() else search.requestFocus() }
        }
        menuBtn = TextView(this).apply {
            text = "☰"; textSize = 20f; gravity = Gravity.CENTER
            contentDescription = "Settings"
            setOnClickListener { showSettings() }
            layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()).apply { marginEnd = (5 * d).toInt() }
        }
        peopleIcon = TextView(this).apply { text = "👥"; textSize = 19f; gravity = Gravity.CENTER }
        peopleBadge = TextView(this).apply {
            textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            val q = (4 * d).toInt(); setPadding(q, 0, q, 0); minWidth = (19 * d).toInt(); visibility = View.GONE
        }
        peopleDot = View(this).apply { visibility = View.GONE }
        peopleBtn = android.widget.FrameLayout(this).apply {
            contentDescription = "Friends"
            setOnClickListener { openTogether() }
            addView(peopleIcon, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(peopleBadge, android.widget.FrameLayout.LayoutParams(WRAP_CONTENT, (19 * d).toInt(), Gravity.END or Gravity.TOP))
            addView(peopleDot, android.widget.FrameLayout.LayoutParams((11 * d).toInt(), (11 * d).toInt(), Gravity.END or Gravity.BOTTOM).apply {
                rightMargin = (3 * d).toInt(); bottomMargin = (3 * d).toInt()
            })
            layoutParams = LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()).apply { marginEnd = (5 * d).toInt() }
        }
        searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 6 * d
            minimumHeight = (56 * d).toInt()
            addView(searchIcon)
            addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clearBtn)
            // one button for everything that's yours (progress, Garage, friends, trips, settings)
            youBtn = YouRing(context).apply {
                contentDescription = L.t("You: progress, Garage, friends, trips and settings")
                setOnClickListener { openSheet("you") }
            }
            addView(youBtn, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply { marginEnd = (5 * d).toInt() })
        }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (10 * d).toInt(), 0, (6 * d).toInt()) }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(chips)
        }
        suggestList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        suggestScroll = ScrollView(this).apply {
            visibility = View.GONE
            elevation = 6 * d
            addView(suggestList)
        }
        topArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            locCard = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; elevation = 5 * d }
            addView(locCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            dutyCard = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; elevation = 5 * d }
            addView(dutyCard, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            addView(chipsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(suggestScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
    }

    private fun renderChips() {
        chips.removeAllViews()
        val solid = opaque(panel)
        fun chip(text: String, style: String = "", onLong: (() -> Unit)? = null, onClick: () -> Unit): TextView {
            val v = TextView(this).apply {
                this.text = text
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val p = (12 * d).toInt()
                setPadding(p + p / 4, (8 * d).toInt(), p + p / 4, (8 * d).toInt())
                minHeight = (40 * d).toInt()
                maxLines = 1
                elevation = 3 * d
                when (style) {
                    // the convoy: always there, easy to spot on any map
                    "convoy" -> { setTextColor(ink); background = bg(solid, accent, 2.2f, 12f, pill = true) }
                    // in a convoy: filled, with how it's going
                    "live" -> { setTextColor(onAccent(accent)); background = bg(accent, onAccent(accent).let { Color.argb(90, Color.red(it), Color.green(it), Color.blue(it)) }, 1f, 12f, pill = true) }
                    "hot" -> { setTextColor(ink); background = bg(solid, readable(accent), 1.2f, 12f, pill = true) }
                    else -> { setTextColor(ink); background = bg(solid, dim(80), 1f, 12f, pill = true) }
                }
                setOnClickListener { onClick() }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }
            chips.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (8 * d).toInt(); gravity = Gravity.CENTER_VERTICAL })
            return v
        }
        // ≡ More: always first, in the same place on every phone (fuel, parking, food, convoy, saved places)
        chips.addView(TextView(this).apply {
            text = "≡"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(readable(accent)); elevation = 3 * d
            background = bg(solid, dim(80), 1f, 20f, pill = true)
            contentDescription = L.t("More: fuel, parking, food, convoy, saved places")
            setOnClickListener { openSheet("more") }
            minHeight = (40 * d).toInt(); minWidth = (46 * d).toInt(); includeFontPadding = false
        }, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = (8 * d).toInt() })
        // the convoy, right after it: its live status while I'm in one
        if (Convoy.active) {
            val n = Convoy.members.values.count { it.state != "left" } + 1
            chip("👥 " + L.t("Convoy") + " · $n · " + L.t(Convoy.status()), "live") { openSheet("convoy") }.apply {
                contentDescription = L.t("Convoy, $n drivers, ${Convoy.status()}")
            }
        } else chip("👥 " + L.t("Convoy"), "convoy") { openSheet("convoy") }
        // a friend's convoy invite: time-sensitive, so it stays in the row
        if (Friends.ready && Friends.core.started) {
            val inv = Friends.core.invites.firstOrNull { it.convoy != Convoy.code }
            if (inv != null) chip("👥 " + L.t("${inv.from} invites you"), "hot") { openSheet("friends") }
        }
        // in a convoy but not on its route: one tap onto it
        if (Convoy.active && !Convoy.onConvoyRoute) Convoy.destination?.let { d0 ->
            chip("➤ " + L.t("Join route") + " · ${d0.name}", "hot") { NavEngine.start(this, d0); MapState.recenter() }
        }
        val homeP = Places.saved("home"); val workP = Places.saved("work")
        for (k in listOf("home", "work")) {
            val p = if (k == "home") homeP else workP
            val name = if (k == "home") "🏠 Home" else "💼 Work"
            val eta = p?.let { Places.cachedEta(it) }?.let { " · ${NavEngine.formatDuration(it.seconds.toDouble())}" } ?: ""
            chip(if (p == null) "$name  +" else name + eta, onLong = { startPicking(k) }) {
                if (p == null) startPicking(k) else NavEngine.plan(this, p)
            }
        }
        // the two places you go to most at this time of day (the rest are in More)
        for (pl in frequentPlaces(2)) {
            val eta = Places.cachedEta(pl)?.let { " · ${NavEngine.formatDuration(it.seconds.toDouble())}" } ?: ""
            val lab = Places.labelled().firstOrNull { Geo.distance(it.place().pos, pl.pos) < 60 }
            chip("${lab?.icon ?: pl.icon.takeIf { it != "🕘" } ?: "★"} ${lab?.label ?: pl.name}$eta", onLong = { showSaveDialog(pl) {} }) { NavEngine.plan(this, pl) }
        }
    }

    /** A colour without its see-through part (chips must stay readable over any map). */
    private fun opaque(c: Int) = Color.rgb(Color.red(c), Color.green(c), Color.blue(c))

    /**
     * The places you go to most around this time (±2 h, weekdays apart from weekends), from your
     * trips; saved places with a label first if there's no history yet. Never Home or Work (they
     * have their own chips).
     */
    private fun frequentPlaces(n: Int): List<Place> {
        val home = Places.saved("home"); val work = Places.saved("work")
        fun isHomeWork(p: Place) = listOfNotNull(home, work).any { Geo.distance(it.pos, p.pos) < 120 }
        val known = (Places.labelled().map { it.place() } + Places.savedPlaces() + Places.recents()).filterNot { isHomeWork(it) }
        if (known.isEmpty()) return emptyList()
        val now = java.util.Calendar.getInstance()
        val hourNow = now.get(java.util.Calendar.HOUR_OF_DAY) + now.get(java.util.Calendar.MINUTE) / 60.0
        val weekendNow = now.get(java.util.Calendar.DAY_OF_WEEK).let { it == java.util.Calendar.SATURDAY || it == java.util.Calendar.SUNDAY }
        val score = HashMap<Place, Double>()
        val cal = java.util.Calendar.getInstance()
        val nowMs = System.currentTimeMillis()
        for (t in Explorer.history()) {
            val pl = known.firstOrNull { it.name.equals(t.place, ignoreCase = true) } ?: continue
            cal.timeInMillis = t.arrivedAt
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60.0
            var dh = kotlin.math.abs(h - hourNow); if (dh > 12) dh = 24 - dh
            val weekend = cal.get(java.util.Calendar.DAY_OF_WEEK).let { it == java.util.Calendar.SATURDAY || it == java.util.Calendar.SUNDAY }
            val days = (nowMs - t.arrivedAt) / 86_400_000.0
            var w = when { dh <= 1 -> 3.0; dh <= 2 -> 2.0; dh <= 4 -> 0.6; else -> 0.2 }
            if (weekend == weekendNow) w *= 1.5
            w *= kotlin.math.exp(-days / 45.0) // recent habits count more
            score[pl] = (score[pl] ?: 0.0) + w
        }
        val ranked = score.entries.sortedByDescending { it.value }.map { it.key }.toMutableList()
        // nothing learned yet: your labelled places
        for (p in Places.labelled().map { it.place() }) if (ranked.size < n && !isHomeWork(p) && ranked.none { Geo.distance(it.pos, p.pos) < 60 }) ranked += p
        val out = ArrayList<Place>()
        for (p in ranked) if (out.size < n && out.none { Geo.distance(it.pos, p.pos) < 60 }) out += p
        return out
    }

    private var moreKey = ""

    /** ≡ More: what used to crowd the chip row. */
    private fun renderMore() {
        val dutyN = LocationTracker.position?.let { Duties.onDutyNear(it, 5000.0).size } ?: 0
        val key = "${theme.name}|${MapState.dark}|${Convoy.active}|$dutyN|${Places.labelled().joinToString { it.label.orEmpty() }}|${Places.saved("home")?.name}|${Places.saved("work")?.name}"
        if (key == moreKey && sheetHost.childCount > 0) return
        moreKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("More"))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.72).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val p = (12 * d).toInt()
        fun section(t: String) = body.addView(label(t, 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding(p / 4, p, 0, p / 2) })
        /** [hot]: that tile's title gets the violet look and [hotSub] under it (pharmacies on duty). */
        fun tiles(vararg items: Triple<String, String, () -> Unit>, hot: String? = null, hotSub: String? = null) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val small = items.size >= 4
            items.forEachIndexed { i, (icon, title, act) ->
                val isHot = title == hot && hotSub != null
                val moon = readable(PoiLayer.MOON)
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(if (small) p * 3 / 4 else p, p, p / 2, p)
                    background = if (isHot) bg(Color.argb(40, 177, 151, 252), moon, 2f, 14f) else bg(dim(22), dim(60), 1f, 14f)
                    isClickable = true
                    setOnClickListener { act() }
                    addView(label(icon, 22f).apply { setTextColor(readable(accent)) }, LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt()))
                    addView(label(title, if (small) 14f else 15f, bold = true).apply { setTextColor(ink); setPadding(0, p / 3, 0, 0); maxLines = 2 })
                    if (isHot) addView(label(hotSub!!, 12f, bold = true).apply { setTextColor(moon); maxLines = 2 })
                }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { if (i < items.size - 1) marginEnd = (if (small) p / 2 else p * 2 / 3) })
            }
            body.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        section("NEARBY")
        tiles(
            Triple("⛽", "Fuel") { closeSheet(); showFuel() },
            Triple("🅿", "Parking") { closeSheet(); showNearby("PARKING NEARBY", listOf("parking")) },
            Triple("🍽", "Food") { closeSheet(); showNearby("FOOD NEARBY", listOf("restaurant", "fast_food", "cafe")) },
            Triple("💊", "Pharmacy") { closeSheet(); showPharmacies() },
            hot = "Pharmacy", hotSub = if (dutyN > 0) "🌙 " + L.t(if (dutyN == 1) "1 on duty" else "$dutyN on duty") else null,
        )
        section("TOGETHER")
        if (Convoy.active) tiles(Triple("👥", "Your convoy") { openSheet("convoy") }, Triple("🙂", "Friends") { openSheet("friends") })
        else tiles(
            Triple("👥", "Start a convoy") { convoyJoinOpen = false; openSheet("convoy") },
            Triple("▦", "Join with a code") { convoyJoinOpen = true; openSheet("convoy") },
            Triple("🙂", "Friends") { openSheet("friends") },
        )
        section("SAVED")
        val saved = listOfNotNull(Places.saved("home")?.let { "🏠" to it }, Places.saved("work")?.let { "💼" to it }) +
            Places.labelled().map { (it.icon ?: "★") to it.place() }
        if (saved.isEmpty()) body.addView(label("Nothing saved yet. Find a place, then tap Save on its card.", 14f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p / 2) })
        for ((icon, pl) in saved) body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p / 2, p * 2 / 3, p / 2, p * 2 / 3); isClickable = true
            setOnClickListener { closeSheet(); NavEngine.plan(this@MainActivity, pl) }
            addView(label(icon, 18f).apply { gravity = Gravity.CENTER; setTextColor(readable(accent)) }, LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(pl.name, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val sub = listOfNotNull(pl.address.split(",").firstOrNull()?.takeIf { it.isNotBlank() },
                    LocationTracker.position?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) }).joinToString(" · ")
                if (sub.isNotBlank()) addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("›", 20f).apply { setTextColor(dim()) })
        })
        body.addView(pill("＋  Add a place") {
            closeSheet(); search.requestFocus()
            Toast.makeText(this, L.t("Search for it, then tap Save on its card"), Toast.LENGTH_LONG).show()
        }.apply { textSize = 15f; setTextColor(readable(accent)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 3 })
    }

    private fun startPicking(key: String) {
        pickFor = key
        search.hint = when (key) { "home" -> "Search for your home address"; "work" -> "Search for your work address"; else -> "Where is the convoy going?" }
        search.setText("")
        search.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
        showRecents()
        refreshChrome()
    }

    private fun showNearby(title: String, types: List<String>) {
        val near = LocationTracker.position
        if (near == null) { Toast.makeText(this, L.t("Waiting for your location…"), Toast.LENGTH_SHORT).show(); return }
        val seq = ++querySeq
        lastResults = emptyList()
        renderResults(emptyList(), "Searching nearby…", header = title, force = true)
        io.execute {
            val local = try { Places.nearbyLocal(types, near) } catch (e: Exception) { emptyList<Place>() }
            if (local.isNotEmpty()) runOnUiThread {
                if (seq == querySeq && !isFinishing) { lastResults = local; renderResults(local, null, header = title, force = true) }
            }
            val r = try { Places.nearbyFull(types, near, local) } catch (e: Exception) { null }
            runOnUiThread {
                if (seq != querySeq || isFinishing) return@runOnUiThread
                lastResults = r ?: emptyList()
                renderResults(lastResults, when {
                    r == null -> "No connection: check mobile data"
                    r.isEmpty() -> "Nothing found nearby"
                    else -> null
                }, header = title, force = true)
            }
        }
    }

    // ------------------------------------------------------------------ pharmacies (on duty first)

    private var pharmShown: List<Place>? = null
    internal fun showPharmaciesForTest() = showPharmacies()

    /** Pharmacies nearby: the ones on duty now first (from the duty list), then the others. */
    private fun showPharmacies() {
        val near = LocationTracker.position
        if (near == null) { Toast.makeText(this, L.t("Waiting for your location…"), Toast.LENGTH_SHORT).show(); return }
        Duties.refresh()
        val seq = ++querySeq
        lastResults = emptyList()
        pharmShown = null
        renderPharmacies(loading = true)
        io.execute {
            val local = try { Places.nearbyLocal(listOf("pharmacy"), near) } catch (e: Exception) { emptyList<Place>() }
            if (local.isNotEmpty()) runOnUiThread { if (seq == querySeq && !isFinishing) { pharmShown = local; renderPharmacies(loading = true) } }
            val r = try { Places.nearbyFull(listOf("pharmacy"), near, local) } catch (e: Exception) { null }
            runOnUiThread {
                if (seq != querySeq || isFinishing) return@runOnUiThread
                pharmShown = r ?: local
                renderPharmacies(loading = false)
            }
        }
    }

    private fun renderPharmacies(loading: Boolean) {
        val near = LocationTracker.position ?: return
        val now = Duties.now()
        val p = (12 * d).toInt()
        val moon = readable(PoiLayer.MOON)
        val closed = Duties.usuallyClosed(now)
        suggestList.removeAllViews()
        fun head(t: String, c: Int) = suggestList.addView(label(t, 12f, bold = true).apply {
            setTextColor(c); letterSpacing = 0.1f; setPadding(p + p / 2, p, p, p / 3)
        })
        head(L.t("PHARMACIES NEARBY"), dim(170))
        val duties = Duties.onDutyNear(near, 15_000.0, now).take(6)
        if (Duties.covers(near)) {
            head("🌙 " + L.t("ON DUTY NOW"), moon)
            if (duties.isEmpty()) suggestList.addView(label(L.t("None on duty right now within 15 km"), 14f).apply { setTextColor(dim()); setPadding(p + p / 2, 0, p, p / 2) })
            duties.forEachIndexed { i, dt -> suggestList.addView(pharmacyRow(Duties.place(dt, now), "🌙 " + L.t("On duty") + " · " + Duties.till(dt, now), moon, dt.phone, first = i == 0)) }
        } else if (Duties.all.isEmpty()) suggestList.addView(label(L.t(if (Duties.updated == 0L) "Getting the duty list…" else "No duty list for this area yet"), 14f).apply { setTextColor(dim()); setPadding(p + p / 2, 0, p, p / 2) })
        val others = pharmShown.orEmpty().filter { o -> duties.none { Geo.distance(it.pos, o.pos) < 60 } }
            .sortedBy { Geo.distance(near, it.pos) }.take(8)
        if (others.isNotEmpty()) {
            head(L.t(if (closed) "CLOSED NOW" else "OTHER PHARMACIES"), dim(170))
            for (o in others) {
                val next = Duties.at(o.pos)?.takeIf { !it.onDuty(now) }?.let { Duties.hoursLine(it, now) }
                val status = when {
                    next != null -> "🌙 $next"
                    closed -> L.t("Closed") + " · " + Duties.opensLine(now)
                    else -> ""
                }
                suggestList.addView(pharmacyRow(o, status, dim(), null, faded = closed))
            }
        } else if (loading) suggestList.addView(label(L.t("Searching nearby…"), 14f).apply { setTextColor(dim()); setPadding(p + p / 2, p / 2, p, p / 2) })
        if (Duties.covers(near)) suggestList.addView(label(Duties.sourceLine(duties.firstOrNull()), 11.5f).apply { setTextColor(dim()); setPadding(p + p / 2, p / 2, p, p) })
        suggestScroll.visibility = View.VISIBLE
        refreshChrome()
        suggestScroll.post {
            val max = (resources.displayMetrics.heightPixels * 0.62f).toInt()
            val lp = suggestScroll.layoutParams
            val want = if (suggestList.height > max) max else WRAP_CONTENT
            if (lp.height != want) { lp.height = want; suggestScroll.layoutParams = lp }
        }
    }

    /** One pharmacy: cross, name, status (on duty till… / closed), distance and area, then Call and Go. */
    private fun pharmacyRow(pl: Place, status: String, statusColor: Int, phone: String?, first: Boolean = false, faded: Boolean = false): View {
        val p = (12 * d).toInt()
        val near = LocationTracker.position
        val moon = readable(PoiLayer.MOON)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p * 3 / 4, p, p * 3 / 4)
            background = when {
                first -> bg(Color.argb(36, 177, 151, 252), moon, 2f, 14f)
                phone != null -> bg(Color.argb(24, 177, 151, 252), radius = 14f)
                else -> null
            }
            if (faded) alpha = 0.6f
            isClickable = true
            setOnClickListener { choose(pl) }
            addView(label("✚", 18f, bold = true).apply {
                gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = bg(Color.rgb(47, 158, 68), radius = 19f)
            }, LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(p, 0, p / 2, 0)
                addView(label(pl.name, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                if (status.isNotBlank()) addView(label(status, 13.5f, bold = true).apply { setTextColor(statusColor); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val where = listOfNotNull(near?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) }, pl.address.substringAfterLast(", ").takeIf { it.isNotBlank() }).joinToString(" · ")
                addView(label(where, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val sq = (42 * d).toInt()
            if (!phone.isNullOrBlank()) addView(label("📞", 17f).apply {
                gravity = Gravity.CENTER; background = bg(Color.TRANSPARENT, dim(120), 1.5f, 12f)
                contentDescription = L.t("Call"); isClickable = true
                setOnClickListener { dial(phone) }
            }, LinearLayout.LayoutParams(sq, sq).apply { marginEnd = p / 2 })
            addView(label("➤", 18f, bold = true).apply {
                gravity = Gravity.CENTER; setTextColor(onAccent(accent)); background = bg(if (faded) dim(60) else accent, radius = 12f)
                contentDescription = L.t("Go"); isClickable = true
                setOnClickListener {
                    search.setText(""); search.clearFocus(); hideKeyboard(); suggestScroll.visibility = View.GONE
                    if (NavEngine.active && !NavEngine.arrived) NavEngine.addStop(pl) else NavEngine.start(this@MainActivity, pl)
                    MapState.recenter(); refreshChrome()
                }
            }, LinearLayout.LayoutParams(sq, sq))
        }.also { it.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(p / 2, p / 6, p / 2, p / 6) } }
    }

    private fun dial(phone: String) {
        try { startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' }))) }
        catch (e: Exception) { Toast.makeText(this, phone, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ fuel nearby (by price)

    /** The stations found (unfiltered); the list re-sorts from it when a chip or the sort changes. */
    private var fuelShown: List<Place>? = null

    internal fun showFuelForTest() = showFuel()

    private fun showFuel() {
        val near = LocationTracker.position
        if (near == null) { Toast.makeText(this, L.t("Waiting for your location…"), Toast.LENGTH_SHORT).show(); return }
        val seq = ++querySeq
        lastResults = emptyList()
        fuelShown = emptyList()
        renderFuel(loading = true)
        io.execute {
            val types = if (Fuel.showChargers) listOf("fuel", "charging_station") else listOf("fuel")
            val local = try { Places.nearbyLocal(types, near) } catch (e: Exception) { emptyList<Place>() }
            if (local.isNotEmpty()) runOnUiThread { if (seq == querySeq && !isFinishing) { fuelShown = local; renderFuel(loading = true) } }
            val r = try { Places.fuelAndChargers(near, Fuel.showChargers) } catch (e: Exception) { null }
            runOnUiThread {
                if (seq != querySeq || isFinishing) return@runOnUiThread
                fuelShown = r ?: local
                renderFuel(loading = false, failed = r == null && local.isEmpty())
            }
        }
    }

    /** Fuel list in the search box: your fuels as chips, Cheapest / Nearest / On my route, prices big. */
    private fun renderFuel(loading: Boolean = false, failed: Boolean = false) {
        val all = fuelShown ?: return
        val near = LocationTracker.position ?: return
        val p = (12 * d).toInt()
        suggestList.removeAllViews()
        // title + Filters
        suggestList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p + p / 2, p, p / 2, 0)
            addView(label("FUEL NEARBY", 12f, bold = true).apply { setTextColor(dim(170)); letterSpacing = 0.1f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val n = (if (Fuel.brands.isNotEmpty()) 1 else 0) + (if (!Fuel.showChargers) 1 else 0) + (if (Fuel.fastOnly) 1 else 0) +
                (if (Fuel.plugs.size < Fuel.plugKinds.size) 1 else 0)
            addView(pill(if (n > 0) "Filters · $n" else "Filters") { openSheet("fuelfilters") }.apply {
                textSize = 13f; setTextColor(readable(accent)); minHeight = (36 * d).toInt()
            })
        })
        // fuel chips
        val kinds = Fuel.fuelKinds.filter { it.first in Fuel.myFuels }.map { it.first } + (if (Fuel.showChargers) listOf("ev") else emptyList())
        suggestList.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(p, p / 2, p, 0)
                for (k in kinds) {
                    val on = k == Fuel.chosen
                    addView(pill(if (k == "ev") "⚡ EV" else Fuel.nameOf(k)) { Fuel.chosen = k; renderFuel(loading) }.apply {
                        textSize = 14f; minHeight = (36 * d).toInt(); val q = (14 * d).toInt(); setPadding(q, 0, q, 0)
                        setTextColor(if (on) onAccent(accent) else ink)
                        background = if (on) bg(accent, radius = 18f, pill = true) else bg(Color.TRANSPARENT, dim(90), 1.5f, 18f, pill = true)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, (36 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
                }
            })
        })
        // sort
        val sorts = listOfNotNull("cheapest" to "Cheapest", "nearest" to "Nearest", if (NavEngine.active && !NavEngine.arrived) "route" to "On my route" else null)
        val cur = Fuel.sort.takeIf { s -> sorts.any { it.first == s } } ?: "cheapest"
        suggestList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; val q = (3 * d).toInt(); setPadding(q, q, q, q)
            background = bg(dim(26), radius = 12f)
            for ((k, t) in sorts) addView(pill(t) { Fuel.sort = k; renderFuel(loading) }.apply {
                textSize = 13f; minHeight = (34 * d).toInt(); setPadding(0, 0, 0, 0)
                setTextColor(if (k == cur) ink else dim()); if (k == cur) background = bg(panel, radius = 10f)
            }, LinearLayout.LayoutParams(0, (34 * d).toInt(), 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(p, p / 2 + p / 4, p, p / 3) })
        val list = Fuel.arrange(all, near).take(15)
        val kind = Fuel.chosen
        if (list.isEmpty()) suggestList.addView(label(when {
            loading -> "Searching nearby…"; failed -> "No connection: check mobile data"; else -> "Nothing found nearby"
        }, 15f).apply { setTextColor(dim(170)); setPadding(p + p / 2, p, p, p) })
        for (pl in list) suggestList.addView(fuelRow(pl, kind, near))
        if (loading && list.isNotEmpty()) suggestList.addView(label("Finding prices…", 13f).apply { setTextColor(dim(150)); setPadding(p + p / 2, p / 2, p, p) })
        if (list.any { it.areaAvg }) suggestList.addView(label("In Greece the price service gives area averages, not each station's own price.", 12f).apply {
            setTextColor(dim(150)); setPadding(p + p / 2, p / 2, p, p)
        })
        // price tags on the map for the stations in the list
        Fuel.pins = list.mapNotNull { pl ->
            val t = if (Fuel.isCharger(pl)) (if (pl.kw > 0) "⚡ ${pl.kw} kW" else "⚡") else Fuel.priceOf(pl, kind)?.let { "${Fuel.nameOf(kind).take(1)} €${"%.2f".format(java.util.Locale.US, it)}" }
            t?.let { pl.pos to it }
        }
        lastResults = list
        map.invalidate()
        suggestScroll.visibility = View.VISIBLE
        refreshChrome()
        suggestScroll.post {
            val max = (resources.displayMetrics.heightPixels * 0.5f).toInt() // leave room for the price tags on the map
            val lp = suggestScroll.layoutParams
            val want = if (suggestList.height > max) max else WRAP_CONTENT
            if (lp.height != want) { lp.height = want; suggestScroll.layoutParams = lp }
        }
    }

    private fun fuelRow(pl: Place, kind: String, near: LatLng): View {
        val p = (12 * d).toInt()
        val charger = Fuel.isCharger(pl)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p - p / 4, p + p / 2, p - p / 4)
            setOnClickListener { choose(pl) }
            addView(label(pl.icon, 20f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams((40 * d).toInt(), WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(p / 2, 0, p / 2, 0)
                addView(label(pl.name, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val det = Fuel.detour(pl)
                val where = listOfNotNull(NavEngine.formatDistance(Geo.distance(near, pl.pos)),
                    det?.let { "+${maxOf(1, (it / 12 / 60).toInt() + 1)} min" }, pl.address.split(",").firstOrNull()?.takeIf { it.isNotBlank() })
                addView(label(where.joinToString(" · "), 13f).apply { setTextColor(dim(170)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val others = if (charger) listOfNotNull(pl.plugs.mapNotNull { k -> Fuel.plugKinds.firstOrNull { it.first == k }?.second }.joinToString(", ").ifBlank { null })
                    else Fuel.fuelKinds.filter { it.first != kind }.mapNotNull { (k, n, _) -> Fuel.priceOf(pl, k)?.let { "$n €${"%.3f".format(java.util.Locale.US, it)}" } }
                if (others.isNotEmpty()) addView(label(others.joinToString(" · "), 12f).apply { setTextColor(dim(200)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                val price = if (charger) (if (pl.kw > 0) "${pl.kw} kW" else "⚡") else Fuel.priceOf(pl, kind)?.let { "€${"%.3f".format(java.util.Locale.US, it)}" } ?: "–"
                addView(label(price, 19f, bold = true).apply { setTextColor(ink); typeface = Typeface.create(theme.typeface, Typeface.BOLD) })
                addView(label(if (charger) "charger" else Fuel.nameOf(kind) + if (pl.areaAvg) " · area avg" else "", 11f).apply { setTextColor(dim(170)); gravity = Gravity.END })
            })
        }
    }

    private fun renderFuelFilters() {
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 24f, topOnly = true)
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label("Fuel filters", 22f, bold = true).apply { setTextColor(ink); typeface = theme.typeface }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("Reset") { Fuel.reset(); renderFuelFilters() }.apply { textSize = 14f; setTextColor(readable(accent)) })
            addView(pill("✕") { closeSheet(); if (fuelShown != null) renderFuel() }.apply { setTextColor(ink) })
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun head(t: String) = body.addView(label(t, 12f, bold = true).apply { setTextColor(dim(170)); letterSpacing = 0.08f; setPadding((6 * d).toInt(), (14 * d).toInt(), 0, (4 * d).toInt()) })
        fun check(title: String, on: Boolean, flip: () -> Unit) = body.addView(switchRow(title, on) { flip() })
        head("MY FUELS (PRICES ON THE MAP)")
        for ((k, n, _) in Fuel.fuelKinds) check(when (k) { "sp95" -> "Unleaded 95"; "lpg" -> "LPG (autogas)"; else -> n }, k in Fuel.myFuels) {
            Fuel.myFuels = if (k in Fuel.myFuels) Fuel.myFuels - k else Fuel.myFuels + k
        }
        head("ELECTRIC")
        check("Show chargers", Fuel.showChargers) { Fuel.showChargers = !Fuel.showChargers }
        for ((k, n) in Fuel.plugKinds) check("Plug: $n", k in Fuel.plugs) { Fuel.plugs = if (k in Fuel.plugs) Fuel.plugs - k else Fuel.plugs + k }
        check("Fast chargers only (50 kW+)", Fuel.fastOnly) { Fuel.fastOnly = !Fuel.fastOnly }
        head("BRANDS (NONE TICKED = ALL)")
        for (b in Fuel.brandList) check(b, b in Fuel.brands) { Fuel.brands = if (b in Fuel.brands) Fuel.brands - b else Fuel.brands + b }
        sheetHost.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(MATCH_PARENT, minOf((resources.displayMetrics.heightPixels * 0.62f).toInt(), (560 * d).toInt())))
        sheetHost.addView(pill("Show stations") { closeSheet(); if (fuelShown != null) renderFuel() else showFuel() }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 26f, pill = true)
        }, LinearLayout.LayoutParams(MATCH_PARENT, (52 * d).toInt()).apply { topMargin = (12 * d).toInt() })
    }

    // ------------------------------------------------------------------ home sheet (recent places)

    private var exploreCard: LinearLayout? = null

    /** Trailblazer: "N landmarks to discover nearby" with your level. */
    private fun renderExploreCard(near: LatLng?) {
        exploreCard?.let { homeSheet.removeView(it) }
        exploreCard = null
        if (theme != Theme.TRAILBLAZER) return
        val todo = near?.let { Explorer.near(it, 1500.0).filterNot { l -> Explorer.isFound(l) } } ?: emptyList()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
            background = bg(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)), radius = 16f)
            setOnClickListener {
                if (todo.isNotEmpty() && near != null) MapState.phone.requestFit(todo.map { it.pos } + near)
                else Toast.makeText(this@MainActivity, L.t("Drive or walk around to find landmarks"), Toast.LENGTH_SHORT).show()
            }
        }
        card.addView(label("★", 20f, bold = true).apply {
            gravity = Gravity.CENTER; setTextColor(onAccent(accent))
            background = bg(accent, radius = 20f, pill = true)
        }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(when {
                near == null -> "Landmarks appear once GPS finds you"
                todo.isEmpty() -> "No new landmarks nearby"
                todo.size == 1 -> "1 landmark to discover nearby"
                else -> "${todo.size} landmarks to discover nearby"
            }, 15f, bold = true).apply { setTextColor(ink) })
            val (have, of) = Explorer.progress()
            addView(label("Level ${Explorer.level} · $have / $of XP · ${Explorer.foundCount} found", 13f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(label("›", 22f).apply { setTextColor(dim()) })
        homeSheet.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            val m = (14 * d).toInt(); setMargins(m, (10 * d).toInt(), m, 0)
        })
        exploreCard = card
    }

    private fun buildHomeSheet() {
        homeTitle = label("Recent", 17f, bold = true).apply { setPadding((18 * d).toInt(), 0, 0, (8 * d).toInt()) }
        homeCards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding((14 * d).toInt(), 0, (8 * d).toInt(), 0) }
        homeHandle = label("", 14f, bold = true).apply {
            gravity = Gravity.CENTER; setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { homeOpen = true }
        }
        homeSheet = object : LinearLayout(this) {
            // a swipe up opens it, a swipe down tucks it away (taps and the cards' sideways scroll still work)
            private var downX = 0f; private var downY = 0f
            private var dragging = false
            private var lastY = 0f; private var lastT = 0L; private var vy = 0f
            override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; lastY = e.rawY; lastT = e.eventTime; vy = 0f }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - downY; val dx = e.rawX - downX
                        if (!dragging && Math.abs(dy) > 10 * d && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                            dragging = true; homeDragStart()
                            // the cards don't also get this touch (no tap, no sideways scroll)
                            val c = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(c); c.recycle()
                        }
                        if (dragging) {
                            val dt = (e.eventTime - lastT).coerceAtLeast(1)
                            vy = vy * 0.5f + (e.rawY - lastY) / dt * 0.5f; lastY = e.rawY; lastT = e.eventTime
                            homeDragTo(dy); return true
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> if (dragging) {
                        dragging = false
                        // a flick decides; otherwise past halfway
                        homeDragEnd(e.rawY - downY, vy)
                        return true
                    }
                }
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            elevation = 8 * d
            isClickable = true
            contentDescription = L.t("Saved & recent")
            // the grab line: always there, says "this pulls up"
            addView(View(context).apply {
                background = bg(Color.argb(140, 150, 150, 160), radius = 3f)
                setOnClickListener { homeOpen = !homeOpen }
            }, LinearLayout.LayoutParams((40 * d).toInt(), (5 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = (9 * d).toInt(); bottomMargin = (6 * d).toInt() })
            // the bar's words and the title share one line: one fades out as the other fades in
            addView(FrameLayout(context).apply {
                addView(homeTitle, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
                addView(homeHandle, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(homeCards)
            })
            // laid out again (new cards, rotation): keep it where it should be
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (!homeDragging && homeAnim?.isRunning != true && (bottom - top != oldBottom - oldTop || homeSheet.translationY == 0f))
                    setHomeFraction(if (homeOpen) 1f else 0f)
            }
        }
    }

    private fun renderHomeSheet() {
        val fav = Places.savedPlaces()
        val rec = (fav + Places.recents().filter { r -> fav.none { Geo.distance(it.pos, r.pos) < 25 } }).take(8)
        homeTitle.text = if (fav.isEmpty()) "Recent" else "Saved & recent"
        homeSheet.background = bg(panel, radius = 22f, topOnly = true)
        homeTitle.setTextColor(ink); homeTitle.typeface = theme.typeface
        homeCards.removeAllViews()
        val near = LocationTracker.position
        // in a convoy: a card to get back to it (who's in, where to, and the next step)
        if (Convoy.active) homeCards.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (12 * d).toInt(); setPadding(p, p - p / 4, p, p - p / 4)
            background = bg(Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, 2f, 14f)
            setOnClickListener { openSheet("convoy") }
            val n = Convoy.members.values.count { it.state != "left" } + 1
            addView(label("👥 " + (Convoy.destination?.let { L.t("To ${it.name}") } ?: L.t("Convoy")), 15f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(label(L.t("$n in it") + " · " + L.t(Convoy.status()), 13f).apply { setTextColor(dim()); maxLines = 1 })
            addView(label(L.t("Open") + " ›", 13f, bold = true).apply { setTextColor(readable(accent)); setPadding(0, (4 * d).toInt(), 0, 0) })
        }, LinearLayout.LayoutParams((176 * d).toInt(), WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
        Parked.place()?.let { car ->
            homeCards.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (12 * d).toInt(); setPadding(p, p - p / 4, p, p - p / 4)
                background = bg(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, 1.5f, 14f)
                setOnClickListener { showPlace(car, dropped = false) }
                addView(label("🚗 " + car.name, 15f, bold = true).apply { setTextColor(ink); maxLines = 1 })
                addView(label(listOfNotNull(near?.let { NavEngine.formatDistance(Geo.distance(it, car.pos)) }, car.address).joinToString(" · "), 13f).apply {
                    setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams((176 * d).toInt(), WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
        }
        for (pl in rec) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (12 * d).toInt()
                setPadding(p, p - p / 4, p, p - p / 4)
                background = bg(Color.argb(28, Color.red(ink), Color.green(ink), Color.blue(ink)), dim(60), 1f, 14f)
                setOnClickListener { homeOpen = false; NavEngine.plan(this@MainActivity, pl) }
            }
            val isSaved = fav.any { it === pl }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(label((if (pl.icon != "🕘" && Places.isSaved(pl)) "${pl.icon} " else "") + pl.name, 15f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (!isSaved) addView(TextView(context).apply {
                    text = "✕"; textSize = 13f; setTextColor(dim(170)); gravity = Gravity.CENTER
                    contentDescription = "Remove from recent"
                    setPadding((8 * d).toInt(), 0, 0, 0)
                    setOnClickListener {
                        Places.removeRecent(pl)
                        card.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(animMs(180)).withEndAction { lastHomeKey = ""; refreshChrome() }.start()
                    }
                })
            })
            val dist = near?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) }
            card.addView(label(listOfNotNull(pl.address.split(",").firstOrNull()?.takeIf { it.isNotBlank() }, dist).joinToString(" · "), 13f).apply {
                setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            Places.cachedEta(pl)?.let { e ->
                val mins = (e.seconds + 30) / 60
                val late = e.delaySeconds / 60
                card.addView(label(if (late >= 2) "● $mins min · +$late traffic" else "● $mins min · clear", 13f, bold = true).apply {
                    setTextColor(readable(if (late >= 2) Color.rgb(255, 120, 80) else Color.rgb(47, 210, 122)))
                    setPadding(0, (4 * d).toInt(), 0, 0)
                })
            }
            homeCards.addView(card, LinearLayout.LayoutParams((176 * d).toInt(), WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
        }
        val anyCard = rec.isNotEmpty() || Parked.get() != null || Convoy.active
        // always laid out open; tucked away = slid down so only the bar shows
        homeTitle.visibility = if (anyCard) View.VISIBLE else View.GONE
        homeCards.visibility = if (anyCard) View.VISIBLE else View.GONE
        (homeCards.parent as? View)?.visibility = if (anyCard) View.VISIBLE else View.GONE
        // tucked away: one slim bar ("⌃ Saved & recent · 🚗 Car 350 m"), the car's distance kept in sight
        homeHandle.setTextColor(dim(210))
        homeHandle.text = listOfNotNull("⌃ " + L.t(if (fav.isEmpty()) "Recent" else "Saved & recent"),
            Parked.place()?.let { car -> "🚗 " + L.t("Car") + (near?.let { " " + NavEngine.formatDistance(Geo.distance(it, car.pos)) } ?: "") },
            if (Convoy.active) "👥 " + L.t("Convoy") else null).joinToString(" · ")
        homeHandle.contentDescription = L.t("Open Saved & recent")
        renderExploreCard(near)
        if (homeAnim?.isRunning != true && !homeDragging) homeSheet.post { setHomeFraction(if (homeOpen) 1f else 0f) }
        // live ETAs for recents, home and work (once a minute at most; TomTom answers are cached 5 min)
        if (near != null && Traffic.active && System.currentTimeMillis() - etaAskedAt > 60_000) {
            etaAskedAt = System.currentTimeMillis()
            val targets = (listOfNotNull(Places.saved("home"), Places.saved("work")) + Places.labelled().map { it.place() } + rec).filter { Places.cachedEta(it) == null }
            if (targets.isNotEmpty()) io.execute {
                var any = false
                for (t in targets) if (Places.refreshEta(t, near) != null) any = true
                if (any) runOnUiThread { etaVersion++; refreshChrome() }
            }
        }
    }

    // ------------------------------------------------------------------ right-hand buttons

    private fun fab(label: String, desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        elevation = 5 * d
        contentDescription = desc
        setOnClickListener { onClick() }
        val s = (50 * d).toInt()
        layoutParams = LinearLayout.LayoutParams(s, s).apply { topMargin = (10 * d).toInt() }
        fabs += this
    }

    private fun buildControls() {
        styleBtn = fab("🎮", "Map style") { showStylePicker() }
        recenterBtn = fab("◎", "Recenter") { MapState.phone.recenter() }
        muteBtn = fab("🔊", "Mute voice") { Voice.muted = !Voice.muted }
        overviewBtn = fab("🗺", "Route overview") {
            val r = NavEngine.route
            if (r.size > 1) MapState.phone.showOverview(r.filterIndexed { i, _ -> i % 5 == 0 } + r.last())
        }
        navMenuBtn = fab("☰", "More") { openSheet("quick") }
        reportBtn = fab("⚠", "Report") { openSheet("report") }
        trafficBtn = fab("🚦", "Traffic on the map") {
            Traffic.showLayer = !Traffic.showLayer
            Toast.makeText(this, L.t(if (Traffic.showLayer) "Traffic shown" else "Traffic hidden"), Toast.LENGTH_SHORT).show()
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // (Map style sits at the bottom right, by the recenter button)
            addView(trafficBtn); addView(reportBtn); addView(muteBtn); addView(overviewBtn); addView(navMenuBtn)
        }
        askText = label("", 16f, bold = true)
        askCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 12 * d
            visibility = View.GONE
            isClickable = true
            val p = (14 * d).toInt(); setPadding(p, p, p, p)
            addView(askText)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill("Not there") { askShown?.let { Alerts.vote(it, false) }; Alerts.clearAsk() },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = (10 * d).toInt() })
                addView(pill("Still there") { askShown?.let { Alerts.vote(it, true) }; Alerts.clearAsk() },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        }
    }

    // ------------------------------------------------------------------ driving card

    private lateinit var stopsRow: LinearLayout
    private var stopsKey = ""

    /** "Via EKO ✕" under the arrival time while there are stops on the way. */
    private fun renderStops() {
        val key = NavEngine.stops.joinToString { it.name } + theme.name + MapState.dark
        if (key == stopsKey) return
        stopsKey = key
        stopsRow.removeAllViews()
        stopsRow.visibility = if (NavEngine.stops.isEmpty()) View.GONE else View.VISIBLE
        for (st in NavEngine.stops) stopsRow.addView(TextView(this).apply {
            text = "Stop: ${st.name}   ✕"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            val q = (12 * d).toInt(); setPadding(q, (7 * d).toInt(), q, (7 * d).toInt())
            background = bg(Color.argb(60, 255, 196, 0), radius = 18f)
            contentDescription = "Stop at ${st.name}. Tap to remove"
            setOnClickListener {
                GameBuilder(this@MainActivity).setMessage("Remove the stop at ${st.name}?")
                    .setPositiveButton("Remove") { _, _ -> NavEngine.removeStop(st) }.setNegativeButton("Keep", null).showT()
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
    }

    private fun buildNavCard() {
        stopsRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        etaBig = TextView(this).apply {
            textSize = 28f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            // long words ("Mission complete" in a wide font) shrink to one line instead of wrapping
            setAutoSizeTextTypeUniformWithConfiguration(16, 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        etaTraffic = TextView(this).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding((10 * d).toInt(), 0, 0, 0); maxLines = 1 }
        etaSmall = TextView(this).apply { textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        shareBtn = pill("Share") { shareEta() }
        convoyBtn = pill("● Convoy") { openSheet("convoy") }.apply { visibility = View.GONE }
        endBtn = pill("End") { NavEngine.stop() }
        navCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 8 * d
            visibility = View.GONE
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(etaBig); addView(etaTraffic)
                })
                addView(etaSmall)
                addView(stopsRow)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(convoyBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() })
            addView(shareBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() })
            addView(endBtn)
        }
    }

    private fun shareEta() {
        val opts = if (LiveShare.active) arrayOf("Send the live link again", "Stop sharing live", "Send arrival time only")
            else arrayOf("Share live location (link)", "Send arrival time only")
        GameBuilder(this@MainActivity).setTitle("Share your trip")
            .setItems(opts) { _, i ->
                when (opts[i]) {
                    "Share live location (link)", "Send the live link again" -> {
                        val link = LiveShare.start()
                        val mins = (NavEngine.remainingSeconds / 60).toInt().coerceAtLeast(1)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "On my way to ${NavEngine.destinationName}, arriving around ${NavEngine.arrivalClock()} (about $mins min). Follow me live: $link")
                        }
                        startActivity(android.content.Intent.createChooser(send, "Share live location"))
                        refreshChrome()
                    }
                    "Stop sharing live" -> { LiveShare.stop(); refreshChrome(); Toast.makeText(this, L.t("Live sharing stopped"), Toast.LENGTH_SHORT).show() }
                    else -> shareEtaText()
                }
            }.show()
    }

    private fun shareEtaText() {
        val mins = (NavEngine.remainingSeconds / 60).toInt().coerceAtLeast(1)
        val msg = "On my way to ${NavEngine.destinationName}: arriving around ${NavEngine.arrivalClock()} (about $mins min)."
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, msg)
        }
        startActivity(android.content.Intent.createChooser(send, "Share arrival time"))
    }

    // ------------------------------------------------------------------ route preview

    private fun buildPreview() {
        previewTitle = TextView(this).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        previewSub = TextView(this).apply { textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        previewClose = pill("✕") { NavEngine.cancelPlan() }
        previewSave = pill("☆") { NavEngine.planPlace?.let { p -> showSaveDialog(p) { renderPreview() } } }.apply {
            textSize = 20f; contentDescription = "Save this place"
        }
        previewBack = pill("←") { NavEngine.cancelPlan(); search.requestFocus(); showSuggestions() }
        previewList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        laterBtn = pill("Leave later") { pickDeparture() }
        convoyNewBtn = pill("👥") { startConvoyFromPreview() }
        startBtn = pill("Start") { NavEngine.startPlanned() }
        preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 10 * d
            visibility = View.GONE
            isClickable = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(previewBack)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(previewTitle); addView(previewSub)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(previewSave)
                addView(previewClose)
            })
            addView(previewList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(laterBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
                addView(convoyNewBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
                addView(startBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
        }
    }

    private fun pickDeparture() {
        if (NavEngine.departAt != null) { NavEngine.setDeparture(null); return }
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, 30) }
        android.app.TimePickerDialog(this, { _, h, m ->
            val t = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m); set(java.util.Calendar.SECOND, 0)
                if (timeInMillis < System.currentTimeMillis() - 60_000) add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            NavEngine.setDeparture(t.timeInMillis)
        }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
    }

    private var infoAsked: Place? = null
    private var infoFuel: String? = null

    /** Fuel prices and stars of the destination, with a Reviews button (loaded once per place). */
    private fun placeInfo(place: Place): View? {
        if (infoAsked != place) {
            infoAsked = place
            infoFuel = place.extra.ifBlank { null }
            val wantsFuel = infoFuel == null && place.icon == "⛽"
            if (wantsFuel || Reviews.enabled) io.execute {
                val f = if (wantsFuel) try { Fuel.forPlace(place) } catch (e: Exception) { null } else null
                if (Reviews.enabled) try { Reviews.details(place) } catch (e: Exception) {}
                runOnUiThread { if (infoAsked == place && !isFinishing) { if (f != null) infoFuel = f; if (NavEngine.planningOn(false)) renderPreview() } }
            }
        }
        val info = Reviews.cached(place)
        val stars = Reviews.stars(info?.rating ?: place.rating, info?.count ?: place.ratings)
        val fuel = infoFuel
        val reviewable = place.icon != "🛣" && place.icon != "🏙" && place.address != "Street" && place.address != "Area"
        // nothing to show (no stars, no prices): no row at all, rather than a lone "Reviews"
        if (stars == null && fuel == null) return null
        val p = (12 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p, p / 2, p / 2, p / 2)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = stars ?: if (Reviews.enabled && info == null) "Loading rating…" else if (fuel != null) "⛽ Prices" else "Reviews"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (stars != null) Color.rgb(255, 193, 7).let { if (Ui.contrast(it, panel) < 3) readable(it) else it } else ink)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (reviewable) top.addView(TextView(this).apply {
            text = "Reviews ›"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(readable(accent))
            setPadding(p, p / 2, p / 2, p / 2)
            isClickable = true
            setOnClickListener { showReviews(place) }
        })
        box.addView(top)
        if (fuel != null) box.addView(TextView(this).apply {
            text = fuel; textSize = 14f; setTextColor(ink)
        })
        return box
    }

    // ------------------------------------------------------------------ place card (tap / hold)

    private fun dropPin(p: LatLng) {
        // the pin shows at once; its street address follows
        val name = pinName
        pinName = null
        showPlace(Place(name ?: "Dropped pin", "Finding the address…", p, "📍"), dropped = true)
        io.execute {
            val a = try { Places.addressAt(p) } catch (e: Exception) { null }
            runOnUiThread {
                if (placeShown?.pos != p) return@runOnUiThread
                // from "Can't find it?": the place under the name you searched for, ready to save
                val pl = if (name != null) Place(name, a?.let { listOf(it.name, it.address).filter { s -> s.isNotBlank() }.joinToString(", ") } ?: "", p, "📍") else a ?: return@runOnUiThread
                showPlace(pl, dropped = name == null)
                if (name != null) showSaveDialog(pl) { showPlace(Places.savedAt(p)?.place() ?: pl, dropped = false) }
            }
        }
    }

    private fun closePlace() {
        placeShown = null
        map.selected = null
        placeCard.visibility = View.GONE
        refreshChrome()
    }

    private fun showPlace(pl: Place, dropped: Boolean) {
        val first = placeShown?.pos != pl.pos
        placeShown = pl
        map.selected = pl
        if (first) {
            placeEta = null
            val near = LocationTracker.position
            if (near != null) io.execute {
                val e = Places.refreshEta(pl, near)
                val f = if (pl.icon == "⛽" && pl.extra.isBlank()) try { Fuel.forPlace(pl) } catch (_: Exception) { null } else null
                if (Reviews.enabled && !dropped && pl.icon != "👥") try { Reviews.details(pl) } catch (_: Exception) {}
                runOnUiThread {
                    if (placeShown?.pos != pl.pos) return@runOnUiThread
                    if (e != null) placeEta = "${(e.seconds + 30) / 60} min"
                    showPlace(if (f != null) pl.copy(extra = f) else placeShown!!, dropped)
                }
            }
        }
        val p = (12 * d).toInt()
        placeCard.removeAllViews()
        placeCard.background = bg(panel, radius = 22f, topOnly = true)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = pl.icon; textSize = 22f; gravity = Gravity.CENTER
            background = bg(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)), radius = 24f)
        }, LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()))
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p, 0, p / 2, 0)
            if (dropped) addView(label("DROPPED PIN", 12f, bold = true).apply { setTextColor(readable(accent)); letterSpacing = 0.08f })
            addView(label(pl.name, 20f, bold = true).apply { setTextColor(ink); typeface = theme.typeface; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            if (pl.address.isNotBlank()) addView(label(pl.address, 14f).apply { setTextColor(dim()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "✕"; textSize = 18f; setTextColor(ink); gravity = Gravity.CENTER
            setOnClickListener { closePlace() }
        }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        placeCard.addView(head)
        val near = LocationTracker.position
        val info = Reviews.cached(pl)
        val facts = listOfNotNull(
            Reviews.stars(info?.rating ?: pl.rating, info?.count ?: pl.ratings),
            near?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) },
            placeEta,
        ).joinToString("  ·  ")
        // facts on the left, Reviews › on the right (not for a dropped pin)
        if (facts.isNotBlank() || (!dropped && pl.icon != "🚗" && pl.icon != "👥")) placeCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, 0)
            addView(label(facts, 15f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (!dropped && pl.icon != "🚗" && pl.icon != "👥") addView(label("Reviews ›", 14f, bold = true).apply {
                setTextColor(readable(accent)); setPadding(p, p, 0, p); setOnClickListener { showReviews(pl) }
            })
        })
        val pharmacy = pl.icon == "💊" && near != null && Duties.covers(pl.pos)
        val duty = if (pharmacy) Duties.at(pl.pos) else null
        if (pharmacy) placeCard.addView(dutyBox(pl, duty), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
        else if (pl.extra.isNotBlank()) placeCard.addView(label(pl.extra, 14f).apply {
            setTextColor(ink); setPadding(p, p / 2, p, p / 2); background = bg(dim(26), radius = 10f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 3 })

        if (pl.icon == "👥") {
            // a friend in the convoy: drive to where they are now
            val navigating = NavEngine.active && !NavEngine.arrived
            val friend = Convoy.members.values.firstOrNull { it.name == pl.name }
            // where they're going: the convoy's destination, or a route of their own
            val theirs = friend?.ownDest ?: Convoy.destination?.takeIf { friend?.state == "driving" }
            if (theirs != null && (!NavEngine.active || Geo.distance(theirs.pos, NavEngine.destination ?: LatLng(0.0, 0.0)) > 250))
                placeCard.addView(pill("➤  Join their route · ${theirs.name}") {
                    closePlace(); NavEngine.start(this@MainActivity, theirs); MapState.recenter()
                }.apply {
                    setTextColor(onAccent(accent)); background = bg(accent, radius = 26f); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p })
            placeCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
                addView(pill(if (theirs != null) "Drive to them now" else "➤  Drive to ${pl.name}") {
                    closePlace()
                    val m = Convoy.members.values.firstOrNull { it.name == pl.name }
                    NavEngine.start(this@MainActivity, (m?.let { Convoy.placeOf(it) } ?: pl)); MapState.recenter()
                }.apply {
                    if (theirs != null) { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 26f) }
                    else { setTextColor(onAccent(accent)); background = bg(accent, radius = 26f) }
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f).apply { marginEnd = if (navigating) p else 0 })
                if (navigating) addView(pill("＋  Stop") { closePlace(); NavEngine.addStop(pl) }.apply {
                    setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 26f)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            placeCard.addView(label("Takes you to where they are right now.", 12f).apply { setTextColor(dim()); setPadding(p / 3, p / 2, 0, 0) })
            appear(placeCard)
            homeSheet.visibility = View.GONE
            return
        }
        if (pl.icon == "🚗") {
            // your parked car: walk back to it (walking directions in Google Maps), or forget it
            placeCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
                addView(pill("🚶  Walk there") { walkTo(pl.pos) }.apply {
                    setTextColor(if (Ui.contrast(Color.WHITE, accent) >= 3) Color.WHITE else Color.BLACK); background = bg(accent, radius = 26f)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f).apply { marginEnd = p })
                addView(pill("Forget") { Parked.clear(); closePlace() }.apply {
                    setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 26f)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            })
            appear(placeCard)
            homeSheet.visibility = View.GONE
            return
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0) }
        val navigating = NavEngine.active && !NavEngine.arrived
        row.addView(pill(if (navigating) "＋  Add stop" else "➤  Go") {
            closePlace()
            if (navigating) NavEngine.addStop(pl) else NavEngine.start(this, pl)
        }.apply { setTextColor(if (Ui.contrast(Color.WHITE, accent) >= 3) Color.WHITE else Color.BLACK); background = bg(accent, radius = 26f) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.6f).apply { marginEnd = p })
        val callable = duty?.phone?.takeIf { it.isNotBlank() && !navigating }
        row.addView(pill(if (callable != null) "📞  " + L.t("Call") else if (navigating) "Go instead" else "Routes") {
            if (callable != null) dial(callable)
            else { closePlace(); if (navigating) NavEngine.start(this, pl) else NavEngine.plan(this, pl) }
        }.apply {
            setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 26f)
            // one line, shrinking a little with big text or long words
            maxLines = 1; setPadding(paddingLeft / 2, paddingTop, paddingRight / 2, paddingBottom)
            setAutoSizeTextTypeUniformWithConfiguration(11, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        placeCard.addView(row)
        if (pharmacy) placeCard.addView(label(Duties.sourceLine(duty) + " · " + L.t("call ahead if it's urgent"), 11.5f).apply {
            setTextColor(dim()); setPadding(p / 3, p / 2, 0, 0)
        })
        // one row of labelled icons for everything else
        val savedNow = Places.isSaved(pl)
        val actions = mutableListOf<Triple<Int, String, () -> Unit>>(
            Triple(R.drawable.ic_act_star, if (savedNow) "Saved" else "Save") {
                showSaveDialog(pl) { showPlace(Places.savedAt(pl.pos)?.place() ?: pl, dropped) }
            },
            Triple(R.drawable.ic_act_share, "Share") { sharePlace(pl) },
            Triple(R.drawable.ic_act_home, "Home") {
                Places.save("home", pl); etaAskedAt = 0
                Toast.makeText(this, L.t("Home saved: ${pl.name}"), Toast.LENGTH_SHORT).show(); closePlace()
            },
            Triple(R.drawable.ic_act_work, "Work") {
                Places.save("work", pl); etaAskedAt = 0
                Toast.makeText(this, L.t("Work saved: ${pl.name}"), Toast.LENGTH_SHORT).show(); closePlace()
            },
        )
        if (!NavEngine.active && pl.icon != "👥") actions += Triple<Int, String, () -> Unit>(R.drawable.ic_act_convoy, "Convoy") {
            closePlace()
            // in a convoy already: this becomes where it goes; otherwise a new convoy there
            if (Convoy.active) suggestToConvoy(pl)
            else withConvoyName { Convoy.create(pl); openSheet("convoy") }
        }
        placeCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
            for ((icon, title, act) in actions) addView(iconAction(icon, title, on = title == "Saved", onClick = act),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        appear(placeCard)
        homeSheet.visibility = View.GONE
    }

    /** A pharmacy's duty: on duty now (hours), on duty later today, or closed with the nearest one on duty. */
    private fun dutyBox(pl: Place, duty: Duties.Duty?): View {
        val p = (12 * d).toInt()
        val now = Duties.now()
        val moon = readable(PoiLayer.MOON)
        val on = duty?.onDuty(now) == true
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p, p * 2 / 3, p, p * 2 / 3)
            background = bg(if (on) Color.argb(40, 177, 151, 252) else dim(22), radius = 12f)
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val later = duty?.takeIf { !on }?.let { Duties.hoursLine(it, now) }
        when {
            on -> {
                box.addView(label("🌙", 20f), LinearLayout.LayoutParams((32 * d).toInt(), WRAP_CONTENT))
                text.addView(label(Duties.hoursLine(duty!!, now).orEmpty(), 15.5f, bold = true).apply { setTextColor(moon) })
                LocationTracker.position?.let { Duties.onDutyNear(it, 3000.0, now).size }?.takeIf { it > 1 }?.let { n ->
                    text.addView(label(L.t("One of $n on duty near you"), 13f).apply { setTextColor(dim()) })
                }
            }
            else -> {
                val closed = Duties.usuallyClosed(now)
                text.addView(label(later ?: if (closed) L.t("Closed now") + " · " + Duties.opensLine(now) else L.t("Not on duty now"), 15f, bold = true)
                    .apply { setTextColor(if (later != null) moon else ink) })
                val nearest = Duties.onDutyNear(pl.pos, 15_000.0, now).firstOrNull()
                if (nearest != null) text.addView(label(L.t("On duty nearby: ") + nearest.name + ", " +
                    NavEngine.formatDistance(Geo.distance(LocationTracker.position ?: pl.pos, nearest.pos)) + " ›", 13.5f, bold = true).apply {
                    setTextColor(moon); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; isClickable = true
                    setOnClickListener { showPlace(Duties.place(nearest, now), dropped = false) }
                })
            }
        }
        box.addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return box
    }

    /** Walking directions to [p]: Google Maps (walking) if it's there, otherwise any map app. */
    private fun walkTo(p: LatLng) {
        val ll = "${p.lat},${p.lon}"
        val gm = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=$ll&mode=w")).setPackage("com.google.android.apps.maps")
        try { startActivity(gm); return } catch (_: Exception) {}
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$ll&travelmode=walking"))) }
        catch (_: Exception) { Toast.makeText(this, L.t("No app to open Google Maps"), Toast.LENGTH_SHORT).show() }
    }

    /** A round icon with a small label under it (the place card's action row). */
    private fun iconAction(icon: Int, title: String, on: Boolean = false, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        contentDescription = title
        setOnClickListener { onClick() }
        val tint = if (on) readable(accent) else ink
        addView(android.widget.ImageView(context).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(tint)
            val q = (11 * d).toInt(); setPadding(q, q, q, q)
            background = bg(if (on) Color.argb(50, Color.red(accent), Color.green(accent), Color.blue(accent)) else dim(30), radius = 23f)
        }, LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()))
        addView(label(title, 12f, bold = true).apply {
            setTextColor(tint); setPadding(0, (4 * d).toInt(), 0, 0); maxLines = 1; gravity = Gravity.CENTER
            // long words (Greek "Κοινοποίηση") shrink to fit under their icon
            setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(MATCH_PARENT, (18 * d).toInt()))
    }

    /** Shows a dialog with its texts (title, message, buttons, list) in the app's language. */
    private fun AlertDialog.Builder.showT(): AlertDialog = show().also { dlg ->
        dlg.window?.decorView?.let { dv -> L.translateTree(dv); Icons.applyTree(dv); dv.viewTreeObserver.addOnPreDrawListener { L.translateTree(dv); Icons.applyTree(dv); true } }
    }

    /** This build's name as people see it: "109", "109.1", "110". */
    private fun shownBuild(): String = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().removePrefix("build-") } catch (_: Exception) { "" }

    // ------------------------------------------------------------------ GameRoute's own pop-ups

    /** Pop-ups open now (newest last): the back button closes the newest first. */
    private val popups = ArrayList<GameDialog>()

    /**
     * A pop-up in GameRoute's own look instead of Android's grey dialog: it slides up from the
     * bottom like the other sheets, in the style's panel, accent, corners and font. Built with the
     * same calls as Android's dialogs (title, message, list, single choice, buttons, a view).
     */
    inner class GameDialog : android.content.DialogInterface {
        var title: CharSequence? = null
        var message: CharSequence? = null
        var view: View? = null
        var items: Array<out CharSequence>? = null
        var checked = -1
        var single = false
        var itemsListener: android.content.DialogInterface.OnClickListener? = null
        var positive: Pair<CharSequence, android.content.DialogInterface.OnClickListener?>? = null
        var negative: Pair<CharSequence, android.content.DialogInterface.OnClickListener?>? = null
        var neutral: Pair<CharSequence, android.content.DialogInterface.OnClickListener?>? = null
        var dismissListener: android.content.DialogInterface.OnDismissListener? = null
        var cancelable = true
        /** A coloured dot before each choice (Your status: green, red, grey). */
        var dots: IntArray? = null
        private var shade: FrameLayout? = null
        private var softBefore: Int? = null
        val isShowing get() = shade != null

        /** The red button: for things that can't be undone. */
        private fun risky(t: CharSequence): Boolean {
            val w = L.t(t.toString()).lowercase()
            return listOf("remove", "delete", "leave", "block", "end", "clear", "αφαίρεση", "διαγραφή", "αποχώρηση", "αποκλεισμός", "τέλος", "καθαρισμός").any { w.startsWith(it) }
        }

        fun show(): GameDialog {
            val root = window.decorView as ViewGroup
            val p = (18 * d).toInt()
            val panelV = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                background = bg(panel, accent, 2f, 24f, topOnly = true)
                setPadding(p, (10 * d).toInt(), p, p + navBottom)
                elevation = 40 * d
                // the grabber
                addView(View(context).apply { background = bg(dim(70), radius = 2f, pill = true) },
                    LinearLayout.LayoutParams((40 * d).toInt(), (4 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = (12 * d).toInt() })
            }
            val body = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            title?.let { t ->
                panelV.addView(label(t.toString(), 21f, bold = true).apply {
                    setTextColor(ink); typeface = theme.typeface; gravity = Gravity.CENTER; setPadding(0, 0, 0, (8 * d).toInt())
                })
            }
            message?.let { m ->
                body.addView(label(m.toString(), 15f).apply {
                    setTextColor(dim()); gravity = if (m.length < 160 && !m.contains("\n\n")) Gravity.CENTER else Gravity.START
                    setLineSpacing(0f, 1.15f); setPadding((4 * d).toInt(), 0, (4 * d).toInt(), (12 * d).toInt())
                })
            }
            view?.let { v -> (v.parent as? ViewGroup)?.removeView(v); body.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
            items?.let { list ->
                list.forEachIndexed { i, it ->
                    val on = single && i == checked
                    body.addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = (52 * d).toInt()
                        val q = (14 * d).toInt(); setPadding(q, (8 * d).toInt(), q, (8 * d).toInt())
                        background = if (on) bg(Color.argb(28, Color.red(ink), Color.green(ink), Color.blue(ink)), accent, 2f, 12f)
                            else bg(Color.argb(14, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 12f)
                        dots?.getOrNull(i)?.let { col ->
                            addView(View(context).apply { background = bg(col, radius = 7f, pill = true) },
                                LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt()).apply { marginEnd = (14 * d).toInt() })
                        }
                        // "Title\nwhat it does": the title bold, the rest under it, quieter
                        val txt = it.toString()
                        val head = txt.substringBefore('\n'); val more = txt.substringAfter('\n', "")
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(head, 16.5f, bold = on || more.isNotEmpty()).apply { setTextColor(ink) })
                            if (more.isNotEmpty()) addView(label(more, 14f).apply { setTextColor(dim()); setLineSpacing(0f, 1.1f); setPadding(0, (2 * d).toInt(), 0, 0) })
                        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        // the chosen one: a ✓ in an accent badge (drawn, so no font or icon swap can hide it)
                        if (on) addView(object : View(context) {
                            val pf = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                            override fun onDraw(cv: android.graphics.Canvas) {
                                pf.style = android.graphics.Paint.Style.FILL; pf.color = accent
                                cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 7 * d, 7 * d, pf)
                                pf.style = android.graphics.Paint.Style.STROKE; pf.strokeWidth = 2.6f * d; pf.color = onAccent(accent)
                                pf.strokeCap = android.graphics.Paint.Cap.ROUND; pf.strokeJoin = android.graphics.Paint.Join.ROUND
                                val w = width.toFloat(); val h = height.toFloat()
                                cv.drawPath(android.graphics.Path().apply { moveTo(w * 0.26f, h * 0.53f); lineTo(w * 0.44f, h * 0.70f); lineTo(w * 0.75f, h * 0.33f) }, pf)
                            }
                        }, LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt()).apply { marginStart = (10 * d).toInt() })
                        setOnClickListener { _ ->
                            if (single) checked = i
                            itemsListener?.onClick(this@GameDialog, i)
                            if (!single) dismiss()
                        }
                    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = (8 * d).toInt() })
                }
            }
            panelV.addView(SheetScroll(0.62).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            neutral?.let { (t, l) ->
                panelV.addView(label(t.toString(), 15f, bold = true).apply {
                    setTextColor(readable(accent)); gravity = Gravity.CENTER; setPadding(0, (10 * d).toInt(), 0, (6 * d).toInt())
                    setOnClickListener { dismiss(); l?.onClick(this@GameDialog, android.content.DialogInterface.BUTTON_NEUTRAL) }
                })
            }
            if (positive != null || negative != null) panelV.addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, (10 * d).toInt(), 0, 0)
                negative?.let { (t, l) ->
                    addView(pill(t.toString()) { dismiss(); l?.onClick(this@GameDialog, android.content.DialogInterface.BUTTON_NEGATIVE) }.apply {
                        setTextColor(ink); background = bg(Color.argb(20, Color.red(ink), Color.green(ink), Color.blue(ink)), dim(90), 1.5f, 14f)
                    }, LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f).apply { if (positive != null) marginEnd = (10 * d).toInt() })
                }
                positive?.let { (t, l) ->
                    val red = risky(t)
                    val fill = if (red) Color.rgb(229, 72, 77) else accent
                    addView(pill(t.toString()) { dismiss(); l?.onClick(this@GameDialog, android.content.DialogInterface.BUTTON_POSITIVE) }.apply {
                        setTextColor(if (red) Color.WHITE else onAccent(accent)); background = bg(fill, radius = 14f)
                    }, LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f))
                }
            })
            val sh = FrameLayout(this@MainActivity).apply {
                setBackgroundColor(Color.argb(165, 0, 0, 0))
                elevation = 60 * d
                setOnClickListener { if (cancelable) cancel() }
                addView(panelV, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            }
            L.translateTree(panelV); Icons.applyTree(panelV)
            panelV.viewTreeObserver.addOnPreDrawListener { L.translateTree(panelV); Icons.applyTree(panelV); true }
            // typing in it: the sheet rides up above the keyboard instead of hiding under it
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(sh) { _, ins ->
                val ime = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                // above the keyboard (less the bar the panel already clears). It used `insets`, which
                // the screen had just set to the keyboard's height itself: the lift came out 0 and the
                // keyboard covered the box (renaming a friend, labelling a place…)
                val nav = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom
                val lift = (ime - nav).coerceAtLeast(0)
                (panelV.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> if (lp.bottomMargin != lift) { lp.bottomMargin = lift; panelV.layoutParams = lp } }
                ins
            }
            if (view != null) { softBefore = window.attributes.softInputMode; window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
            root.addView(sh, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            androidx.core.view.ViewCompat.requestApplyInsets(sh)
            shade = sh; popups += this
            sh.alpha = 0f; sh.animate().alpha(1f).setDuration(animMs(150)).start()
            panelV.translationY = 80 * d
            panelV.animate().translationY(0f).setDuration(animMs(220)).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
            return this
        }

        override fun cancel() = dismiss()
        override fun dismiss() {
            val sh = shade ?: return
            shade = null; popups -= this
            softBefore?.let { window.setSoftInputMode(it); softBefore = null }
            (sh.parent as? ViewGroup)?.removeView(sh)
            dismissListener?.onDismiss(this)
        }
    }

    /** Builds a [GameDialog] with the same calls as Android's AlertDialog.Builder. */
    inner class GameBuilder(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        private val g = GameDialog()
        private fun s(t: CharSequence?) = t
        fun setTitle(t: CharSequence?) = apply { g.title = s(t) }
        fun setMessage(t: CharSequence?) = apply { g.message = s(t) }
        fun setView(v: View?) = apply { g.view = v }
        fun setCancelable(c: Boolean) = apply { g.cancelable = c }
        fun setItems(items: Array<out CharSequence>, l: android.content.DialogInterface.OnClickListener?) = apply { g.items = items; g.single = false; g.itemsListener = l }
        fun setSingleChoiceItems(items: Array<out CharSequence>, checked: Int, l: android.content.DialogInterface.OnClickListener?) = apply { g.items = items; g.single = true; g.checked = checked; g.itemsListener = l }
        fun setPositiveButton(t: CharSequence, l: android.content.DialogInterface.OnClickListener?) = apply { g.positive = t to l }
        fun setNegativeButton(t: CharSequence, l: android.content.DialogInterface.OnClickListener?) = apply { g.negative = t to l }
        fun setNeutralButton(t: CharSequence, l: android.content.DialogInterface.OnClickListener?) = apply { g.neutral = t to l }
        fun setOnDismissListener(l: android.content.DialogInterface.OnDismissListener?) = apply { g.dismissListener = l }
        fun setDots(c: IntArray) = apply { g.dots = c }
        fun show(): GameDialog = g.show()
        fun showT(): GameDialog = g.show()
    }

    /** For tests: the pop-ups open now. */
    internal fun popupsForTest(): List<GameDialog> = popups.toList()
    /** The bottom bar's height (not the keyboard's). */
    private var navBottom = 0

    private fun sharePlace(pl: Place) {
        val url = "https://www.google.com/maps/search/?api=1&query=${pl.pos.lat},${pl.pos.lon}"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${pl.name}\n${pl.address}\n$url".replace("\n\n", "\n"))
        }
        startActivity(Intent.createChooser(send, "Share place"))
    }

    private fun showReviews(place: Place) {
        val info = Reviews.cached(place)
        if (info == null || info.reviews.isEmpty()) {
            // no key (or nothing from Google): its reviews are in Google Maps
            val url = info?.mapsUrl ?: Reviews.mapsLink(place)
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            catch (e: Exception) { Toast.makeText(this, L.t("No app to open Google Maps"), Toast.LENGTH_SHORT).show() }
            return
        }
        val p = (16 * d).toInt()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, p) }
        for (r in info.reviews) {
            list.addView(TextView(this).apply {
                text = "★".repeat(r.stars.coerceIn(0, 5)) + "☆".repeat((5 - r.stars).coerceIn(0, 5)) + "  ${r.author} · ${r.when_}"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, p, 0, p / 4)
            })
            list.addView(TextView(this).apply { text = r.text; textSize = 14f })
        }
        GameBuilder(this@MainActivity)
            .setTitle(listOfNotNull(place.name, Reviews.stars(info.rating, info.count)).joinToString("  "))
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Close", null)
            .setNeutralButton("All on Google Maps") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.mapsUrl ?: Reviews.mapsLink(place)))) } catch (_: Exception) {}
            }
            .showT()
    }

    private fun renderPreview() {
        val opts = NavEngine.options
        val place = NavEngine.planPlace
        previewTitle.setTextColor(ink); previewTitle.typeface = theme.typeface
        previewSub.setTextColor(dim()); previewClose.setTextColor(ink); previewBack.setTextColor(ink)
        val savedHere = place?.let { Places.isSaved(it) } == true
        previewSave.text = if (savedHere) "★" else "☆"
        previewSave.setTextColor(if (savedHere) readable(accent) else ink)
        previewTitle.text = when {
            NavEngine.planError != null -> NavEngine.planError
            NavEngine.planLoading -> "Finding routes…"
            opts.size == 1 -> "1 route"
            else -> "${opts.size} routes"
        }
        val depart = NavEngine.departAt
        previewSub.text = listOfNotNull(
            place?.name,
            if (depart != null) "leaving ${NavEngine.clock(depart)}" else if (Traffic.active) "live traffic" else null,
        ).joinToString(" · ")
        previewList.removeAllViews()
        if (place != null) placeInfo(place)?.let { previewList.addView(it) }
        // the route stops where the car road ends (a cave, a peak)
        NavEngine.endNote?.let { note ->
            previewList.addView(label("⚠ $note", 13.5f).apply {
                setTextColor(ink); val q = (10 * d).toInt(); setPadding(q, q * 3 / 4, q, q * 3 / 4)
                background = bg(Color.argb(45, 255, 170, 60), Color.rgb(255, 170, 60), 1.5f, 12f)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
        val fastest = NavEngine.fastestIndex()
        val p = (12 * d).toInt()
        opts.forEachIndexed { i, o ->
            val sel = i == NavEngine.selected
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(p, p - p / 4, p, p)
                isClickable = true
                setOnClickListener { NavEngine.select(i) }
                background = bg(
                    if (sel) Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.TRANSPARENT,
                    if (sel) accent else dim(70), if (sel) 2f else 1f, 12f,
                )
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            top.addView(TextView(this).apply {
                text = if (NavEngine.timeKnown(o)) NavEngine.formatDuration(o.seconds) else "· · ·"
                textSize = if (sel) 22f else 19f; typeface = theme.typeface
                setTextColor(if (sel) readable(accent) else ink)
            })
            top.addView(TextView(this).apply {
                text = "  ${NavEngine.formatDistance(o.distance)}" + (if (NavEngine.timeKnown(o)) " · arrive ${NavEngine.optionArrival(o)}" else " · checking traffic")
                textSize = 14f; setTextColor(dim())
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val tag = when {
                NavEngine.breaksAvoid(o) -> if (o.hasToll && NavEngine.avoidTolls) "TOLLS" else "MOTORWAY"
                o.trafficSeconds != null && i == fastest && opts.size > 1 -> "FASTEST"
                o.delaySeconds >= 60 -> "+${o.delaySeconds / 60} min traffic"
                else -> null
            }
            if (tag != null) top.addView(TextView(this).apply {
                text = tag; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(readable(if (tag == "FASTEST") accent else Color.rgb(255, 120, 90)))
            })
            row.addView(top)
            val tollText = if (o.tollCost > 0) "${Tolls.money(o.tollCost)} tolls" else if (o.hasToll) "tolls" else null
            if (o.via.isNotBlank() || tollText != null) row.addView(TextView(this).apply {
                text = listOfNotNull(o.via.takeIf { it.isNotBlank() }?.let { "via $it" }, tollText).joinToString("  ·  ")
                textSize = 13f; setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.START
            })
            row.addView(TrafficBar(this, o), LinearLayout.LayoutParams(MATCH_PARENT, (5 * d).toInt()).apply { topMargin = (7 * d).toInt() })
            previewList.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
        preview.background = bg(panel, radius = 22f, topOnly = true)
        laterBtn.text = if (depart != null) "Leave now" else "Leave later"
        laterBtn.setTextColor(readable(accent))
        convoyNewBtn.setTextColor(ink); convoyNewBtn.background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f)
        convoyNewBtn.contentDescription = "Drive there as a convoy"
        laterBtn.background = bg(Color.TRANSPARENT, accent, 1.5f, 14f)
        startBtn.isEnabled = opts.isNotEmpty()
        startBtn.alpha = if (opts.isNotEmpty()) 1f else 0.5f
        startBtn.setTextColor(onAccent(accent))
        startBtn.typeface = theme.typeface
        startBtn.background = bg(accent, radius = 14f)
    }

    // ------------------------------------------------------------------ faster route offer

    private fun buildOffer() {
        offerTitle = TextView(this).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; text = "⚡ Faster route found" }
        offerSub = TextView(this).apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        offerVia = TextView(this).apply { textSize = 14f }
        keepBtn = pill("Keep current") { NavEngine.declineOffer() }
        switchBtn = pill("Switch") { NavEngine.acceptOffer() }
        offerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 12 * d
            visibility = View.GONE
            isClickable = true
            val p = (14 * d).toInt()
            setPadding(p + p / 4, p, p + p / 4, p)
            addView(offerTitle); addView(offerSub); addView(offerVia)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(keepBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = (10 * d).toInt() })
                addView(switchBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
        }
    }

    private fun renderOffer() {
        val o = NavEngine.offer ?: return
        val green = Color.rgb(61, 220, 132)
        offerCard.background = bg(panel, green, 2f, 18f)
        offerTitle.setTextColor(ink); offerTitle.typeface = theme.typeface
        val arrive = NavEngine.clock(System.currentTimeMillis() + (o.option.seconds * 1000).toLong())
        offerSub.text = "Saves ${(o.savingSeconds + 30) / 60} min · arrive $arrive"
        offerSub.setTextColor(readable(green))
        val first = o.option.steps.firstOrNull { it.type != "depart" }
        offerVia.text = listOfNotNull(
            o.option.via.takeIf { it.isNotBlank() }?.let { "Via $it" },
            first?.let { "Next: ${it.text}" },
        ).joinToString("\n")
        offerVia.setTextColor(dim(200))
        keepBtn.setTextColor(ink)
        keepBtn.background = bg(Color.TRANSPARENT, dim(200), 1.5f, 14f)
        val left = (NavEngine.OFFER_SECONDS - (System.currentTimeMillis() - o.createdAt) / 1000).coerceAtLeast(0)
        switchBtn.text = if (NavEngine.autoSwitch) "Switch · ${left}s" else "Switch"
        keepBtn.text = if (NavEngine.autoSwitch) "Keep current" else "Keep · ${left}s"
        switchBtn.setTextColor(Color.rgb(11, 36, 22))
        switchBtn.background = bg(green, radius = 14f)
    }

    // ------------------------------------------------------------------ arrival card

    private fun renderTrip(trip: Explorer.Trip) {
        val t = theme
        tripCard.removeAllViews()
        val p = (20 * d).toInt()
        tripCard.setPadding(p, p, p, p)
        tripCard.background = bg(panel, accent, 2f, 22f)
        tripCard.addView(label(Explorer.completeTitle(t).uppercase(), 13f, bold = true).apply {
            setTextColor(onAccent(accent)); letterSpacing = 0.12f; gravity = Gravity.CENTER
            val q = (8 * d).toInt(); setPadding(q * 2, q, q * 2, q)
            background = bg(accent, radius = 8f)
        })
        tripCard.addView(label(trip.place, 22f, bold = true).apply {
            setTextColor(ink); typeface = t.typeface; gravity = Gravity.CENTER; setPadding(0, (14 * d).toInt(), 0, 0)
        })
        tripCard.addView(label("Arrived ${NavEngine.clock(trip.arrivedAt)}", 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER })
        if (Convoy.active && Convoy.meAlong) tripCard.addView(label("Convoy: you drove along (not in the race)", 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, (6 * d).toInt(), 0, 0) })
        Convoy.myPlace()?.let { (place, of) ->
            val nth = when (place) { 1 -> "1st 👑"; 2 -> "2nd"; 3 -> "3rd"; else -> "${place}th" }
            tripCard.addView(label("Convoy: $nth of $of", 15f, bold = true).apply { setTextColor(readable(accent)); gravity = Gravity.CENTER; setPadding(0, (6 * d).toInt(), 0, 0) })
            // the others still racing: follow them live (the card stays until everyone's in)
            tripCard.addView(pill("🏁  " + L.t("See the race")) { NavEngine.dismissTrip(); openSheet("convoy") }.apply {
                textSize = 15f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f)
            }, LinearLayout.LayoutParams(MATCH_PARENT, (46 * d).toInt()).apply { topMargin = (8 * d).toInt() })
        }
        // the people I drove with who aren't friends yet: one tap to add
        if (Friends.ready && Convoy.active) {
            val c = Friends.core
            val others = Convoy.members.values.filter { it.fc.isNotBlank() && !c.isFriend(it.fid) && it.fid !in c.blocked }
            if (others.isNotEmpty()) {
                tripCard.addView(label("DROVE WITH YOU", 11.5f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.1f; setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt()) })
                for (m in others.take(4)) tripCard.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                    addView(avatar(m.name, m.fid, false, 32), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = (10 * d).toInt() })
                    addView(label(m.name, 15f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    if (c.requested(m.fc)) addView(label("Request sent", 13.5f).apply { setTextColor(dim()) })
                    else addView(pill("＋ Add") { }.apply {
                        textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f)
                        setOnClickListener { v -> sendFriendRequest(m.fc, m.name) { (v as TextView).text = L.t("Request sent"); v.background = null; v.isEnabled = false } }
                    })
                })
            }
        }
        val speed = if (MapState.imperial) "${(trip.avgKmh * 0.621).toInt()} mph" else "${trip.avgKmh} km/h"
        tripCard.addView(label(listOfNotNull(NavEngine.formatDuration(trip.seconds.toDouble()), NavEngine.formatDistance(trip.meters),
            if (trip.savedSeconds >= 60) L.t("${trip.savedSeconds / 60} min saved") else L.t("avg $speed")).joinToString(" · "), 14f).apply {
            setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, 0)
        })
        // the XP: one line with the level bar; tap it for what each part earned
        val (have, of) = Explorer.progress()
        val up = Explorer.level > trip.levelBefore
        val gained = (if (up) have else minOf(trip.xp, have)).toFloat() / maxOf(of, 1)
        tripCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val q = (12 * d).toInt(); setPadding(q, q * 3 / 4, q / 2, q * 3 / 4)
            background = bg(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)), 1.2f, 14f)
            isClickable = true; setOnClickListener { showXpParts(trip) }
            contentDescription = L.t("+${trip.xp} XP. Tap for the details")
            addView(label(if (trip.xp == 0) L.t("No XP") else "+${trip.xp} XP", 22f, bold = true).apply { setTextColor(readable(accent)); typeface = t.typeface; setPadding(0, 0, q, 0) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(object : View(context) {
                    private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    override fun onDraw(c: Canvas) {
                        val r = height / 2f; val f = have.toFloat() / maxOf(of, 1)
                        pt.color = dim(50); c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, pt)
                        pt.color = accent; c.drawRoundRect(0f, 0f, width * (f - gained).coerceAtLeast(0f), height.toFloat(), r, r, pt)
                        pt.color = Color.rgb(255, 46, 136); c.drawRoundRect(width * (f - gained).coerceAtLeast(0f), 0f, width * f, height.toFloat(), r, r, pt)
                    }
                }, LinearLayout.LayoutParams(MATCH_PARENT, (8 * d).toInt()))
                addView(label(if (trip.xp == 0) L.t("trips under 200 m") else if (up) L.t("Level up! Level ${Explorer.level}") else L.t("Level ${Explorer.level} · ${of - have} to go"), 13f, bold = up).apply {
                    setTextColor(if (up) readable(accent) else dim()); setPadding(0, (4 * d).toInt(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("›", 22f).apply { setTextColor(dim()); setPadding(q / 2, 0, 0, 0) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (14 * d).toInt() })
        if (up) pendingLevelFrom = trip.levelBefore
        // the Garage's arrival effect (you've stopped: the card only shows once you arrive)
        val fx = Xp.chosen("arrival").id
        if (fx != "arrival_classic" && tripFxFor !== trip && Perf.anim == Perf.FULL) {
            tripFxFor = trip
            tripOverlay.addView(ArrivalFx(this, fx), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        tripCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // both the same height, one line each (a two-line Parking button broke its outline)
            val h = (52 * d).toInt()
            addView(pill("🅿  " + L.t("Parking")) {
                if (NavEngine.arrived) NavEngine.stop()
                NavEngine.dismissTrip(); showNearby("PARKING NEARBY", listOf("parking"))
            }.apply {
                setTextColor(ink); textSize = 15f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0); minHeight = 0; minimumHeight = 0
                background = bg(Color.TRANSPARENT, dim(160), 1.5f, 14f)
                contentDescription = L.t("Parking nearby")
            }, LinearLayout.LayoutParams(0, h, 1f).apply { marginEnd = (10 * d).toInt() })
            // Done: the route is over too (no "Mission complete" bar left behind)
            addView(pill("Done") { if (NavEngine.arrived) NavEngine.stop(); NavEngine.dismissTrip() }.apply {
                setTextColor(onAccent(accent)); typeface = t.typeface; background = bg(accent, radius = 14f)
                maxLines = 1; gravity = Gravity.CENTER; setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0); minHeight = 0; minimumHeight = 0
            }, LinearLayout.LayoutParams(0, h, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (18 * d).toInt() })
    }

    // ------------------------------------------------------------------ state → UI

    /** Colours everything to match the map theme and shows what fits the moment. */
    private var lastLookKey = ""
    private var tripLook = ""
    /** A level-up waiting to be shown once the arrival card is closed. */
    private var pendingLevelFrom = 0
    private var tripFxFor: Explorer.Trip? = null

    private var screenKept = false

    private fun refreshChrome() {
        // the screen stays on only while a route is being followed or chosen; otherwise the
        // phone dims and locks as usual (the biggest battery saver of all)
        val keep = NavEngine.active || NavEngine.planningOn(false)
        if (keep != screenKept) {
            screenKept = keep
            if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val t = theme
        val lookKey = "${t.name}|${MapState.dark}"
        if (lookKey != lastLookKey) {
            val first = lastLookKey.isEmpty()
            lastLookKey = lookKey
            if (!first && suggestScroll.visibility == View.VISIBLE) { main.post { if (lastResults.isNotEmpty()) renderResults(lastResults, null) else showSuggestions() } }
        }
        val nav = NavEngine.active
        val planning = NavEngine.planningOn(false)
        val searching = search.hasFocus() || suggestScroll.visibility == View.VISIBLE || pickFor != null
        if (searching || placeShown != null) { if (::dutyCard.isInitialized && dutyCard.visibility == View.VISIBLE) { dutyCard.visibility = View.GONE; dutyKey = "" } }
        else syncDutyCard()

        searchCard.background = bg(panel, accent, 1.5f, 18f, pill = true)
        suggestScroll.background = bg(panel, radius = 18f)
        search.setTextColor(ink)
        search.setHintTextColor(dim(150))
        search.typeface = t.typeface
        searchIcon.setTextColor(readable(accent))
        searchIcon.text = if (searching) "←" else "⌕"
        searchIcon.contentDescription = if (searching) "Back" else "Search"
        clearBtn.setTextColor(ink)
        menuBtn.setTextColor(ink)
        menuBtn.background = bg(Color.argb(30, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f, pill = true)
        peopleIcon.setTextColor(readable(accent))
        peopleBtn.background = bg(Color.argb(30, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f, pill = true)
        peopleBadge.background = bg(Color.rgb(230, 60, 60), panel, 1.5f, 10f)
        peopleDot.background = bg(Color.rgb(72, 199, 116), panel, 1.5f, 6f)
        // while typing, the box gets the room
        peopleBtn.visibility = if (searching || pickFor != null) View.GONE else View.VISIBLE
        youBtn.visibility = peopleBtn.visibility
        youBtn.invalidate()
        updatePeopleBadge()
        if (pickFor == null) search.hint = "Where to?"
        for (f in fabs) {
            f.setTextColor(ink)
            f.background = bg(panel, accent, 1.5f, 16f, pill = true)
        }
        navCard.background = bg(panel, radius = 22f, topOnly = true)
        etaSmall.setTextColor(ink)
        etaBig.typeface = t.typeface
        endBtn.setTextColor(Color.WHITE)
        endBtn.background = bg(Color.rgb(200, 40, 40), radius = 14f)
        shareBtn.setTextColor(ink)
        shareBtn.background = bg(Color.TRANSPARENT, accent, 1.5f, 14f)

        navCard.visibility = if (nav) View.VISIBLE else View.GONE
        if (planning) appear(preview) else preview.visibility = View.GONE
        searchCard.visibility = if (nav || planning) View.GONE else View.VISIBLE
        val chipsWas = chipsScroll.visibility; val homeWas = homeSheet.visibility
        chipsScroll.visibility = if (nav || planning || searching) View.GONE else View.VISIBLE
        homeSheet.visibility = if (nav || planning || searching || placeShown != null || (Places.recents().isEmpty() && Places.savedPlaces().isEmpty() && Parked.get() == null && !Convoy.active && t != Theme.TRAILBLAZER)) View.GONE else View.VISIBLE
        if (chipsWas != chipsScroll.visibility || homeWas != homeSheet.visibility) lastHomeKey = ""
        if ((nav || planning) && suggestScroll.visibility == View.VISIBLE) closeSearch()
        val v = MapState.phone
        recenterBtn.visibility = if ((v.following && v.userBearing == null && !v.overview && !v.zoomedAway) || planning) View.GONE else View.VISIBLE
        muteBtn.visibility = if (nav) View.VISIBLE else View.GONE
        muteBtn.text = if (Voice.muted) "🔇" else "🔊"
        // while driving only three: sound, report, ☰ (style, traffic and overview live in ☰)
        overviewBtn.visibility = View.GONE
        styleBtn.visibility = if (nav || planning || searching) View.GONE else View.VISIBLE
        navMenuBtn.visibility = if (nav) View.VISIBLE else View.GONE
        // update 100: Report is gone (unused), Show traffic lives in Settings › Map
        reportBtn.visibility = View.GONE
        trafficBtn.visibility = View.GONE
        trafficBtn.alpha = if (Traffic.showLayer) 1f else 0.45f
        val ask = Alerts.askAbout
        askCard.visibility = if (ask != null && !planning) View.VISIBLE else View.GONE
        if (ask != null && ask !== askShown) {
            askShown = ask
            val tp = Alerts.typeOf(ask.type)
            askText.text = "${tp.glyph}  ${tp.title} reported here: still there?"
        }
        if (ask != null) {
            askCard.background = bg(panel, Color.rgb(255, 150, 0), 2f, 18f)
            askText.setTextColor(ink)
            for (i in 0 until (askCard.getChildAt(1) as LinearLayout).childCount) {
                val b = (askCard.getChildAt(1) as LinearLayout).getChildAt(i) as TextView
                b.setTextColor(if (i == 1) Color.BLACK else ink)
                b.background = if (i == 1) bg(Color.rgb(255, 170, 40), radius = 14f) else bg(Color.TRANSPARENT, dim(160), 1.5f, 14f)
            }
        }
        controls.visibility = if (planning || searching) View.GONE else View.VISIBLE
        // driving: only the turn, speed and arrival; the buttons fade (a tap on the map brings them back)
        val quiet = nav && !NavEngine.arrived && Later.driving && System.currentTimeMillis() > controlsUntil
        val ctlAlpha = if (quiet) 0f else 1f
        if (controls.alpha != ctlAlpha && controls.animate() != null) controls.animate().alpha(ctlAlpha).setDuration(if (quiet) 600 else 150).start()
        for (i in 0 until controls.childCount) controls.getChildAt(i).isClickable = !quiet
        if (nav && !quiet && Later.driving) { main.removeCallbacks(quietTick); main.postDelayed(quietTick, (controlsUntil - System.currentTimeMillis()).coerceIn(500, 5100)) }
        // messages kept for when you stop
        val held = Later.count
        laterPill.visibility = if (held > 0 && (nav || !Later.driving) && sheetOverlay.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        if (held > 0) {
            laterPill.text = if (Later.driving) "✉ " + L.t(if (held == 1) "1 message for when you stop" else "$held messages for when you stop") else "✉ " + L.t(if (held == 1) "1 message · tap to read" else "$held messages · tap to read")
            laterPill.setTextColor(ink); laterPill.background = bg(opaque(panel), Color.rgb(255, 194, 61), 1.5f, 19f, pill = true)
        }
        // stopped (the trip is over, or arrived and the card is closed): show what waited
        if (held > 0 && !Later.driving && (!nav || NavEngine.arrived) && NavEngine.lastTrip == null && sheetOverlay.visibility != View.VISIBLE) main.post { showLater() }
        val homeKey = "${t.name}|${MapState.dark}|${MapState.imperial}|$etaVersion|${Explorer.version}|${Explorer.xp}|${Places.recents().joinToString { it.name }}|" +
            "${Places.saved("home")?.name}|${Places.saved("work")?.name}|${LocationTracker.position != null}|${Parked.get()?.at}|${Convoy.active}|${Convoy.members.size}|${Convoy.destination?.name}|${Convoy.onConvoyRoute}|${Convoy.status()}|${java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)}|${Places.labelled().joinToString { it.label + it.icon }}|" +
            (if (Friends.ready && Friends.core.started) "${Friends.core.invites.joinToString { it.convoy + it.count }}|${Friends.core.requests.size}" else "")
        if (homeKey != lastHomeKey) {
            lastHomeKey = homeKey
            if (chipsScroll.visibility == View.VISIBLE) renderChips()
            if (homeSheet.visibility == View.VISIBLE) renderHomeSheet()
        }
        if (planning) {
            val o = NavEngine.options
            val key = "${t.name}|${MapState.dark}|${System.identityHashCode(o)}|${NavEngine.selected}|${o.sumOf { it.trafficSeconds ?: 0 }}|${o.count { it.timingFailed }}|" +
                "${NavEngine.departAt}|${NavEngine.planLoading}|${NavEngine.planError}"
            if (key != lastPreviewKey) { lastPreviewKey = key; renderPreview() }
        } else lastPreviewKey = ""
        val showOffer = nav && NavEngine.offer != null
        if (showOffer && offerCard.visibility != View.VISIBLE) { main.removeCallbacks(ticker); main.postDelayed(ticker, 1000) }
        offerCard.visibility = if (showOffer) View.VISIBLE else View.GONE
        if (showOffer) renderOffer()
        if (Convoy.proposal != null) askProposal()
        if (sheetOverlay.visibility == View.VISIBLE) when (sheetKind) {
            "style" -> renderStylePicker()
            "settings" -> renderSettings()
            "report" -> renderReport()
            "trips" -> renderTrips()
            "recap" -> renderRecap()
            "offline" -> renderOffline()
            "convoy" -> if (Convoy.counting) closeSheet() else renderConvoy() // the countdown shows on the map
            "quick" -> renderQuick()
            "more" -> renderMore()
            "you" -> renderYou()
            "progress" -> renderProgress()
            "garage" -> renderGarage()
            "convoyinvite" -> renderConvoyInvite()
            "friends" -> renderFriends()
            "addfriend" -> renderAddFriend()
            "fuelfilters" -> {} // drawn once when opened, so its switches keep their scroll place
        }
        val trip = NavEngine.lastTrip
        tripOverlay.visibility = if (trip != null) View.VISIBLE else View.GONE
        if (trip != null && (trip !== shownTrip || lookKey != tripLook)) { shownTrip = trip; tripLook = lookKey; renderTrip(trip) }
        if (trip == null) {
            shownTrip = null
            // the arrival card is closed: now the level-up (never while driving)
            if (pendingLevelFrom > 0 && !Later.driving && (!NavEngine.active || NavEngine.arrived)) { val f = pendingLevelFrom; pendingLevelFrom = 0; main.post { showLevelUp(f) } }
            // a level reached without the phone's arrival card (driving without a route, only on
            // Android Auto, or the app closed in between): celebrated the next time you're parked
            else if (pendingLevelFrom == 0 && !Later.driving && !NavEngine.active && sheetKind.isEmpty()) {
                val seen = celebratedLevel()
                if (Explorer.level > seen) main.post { showLevelUp(seen) }
            }
        }
        if (nav) {
            etaTraffic.text = ""
            when {
                NavEngine.arrived -> { etaBig.text = "Mission complete"; etaBig.setTextColor(ink); etaSmall.text = NavEngine.destinationName }
                NavEngine.loading || NavEngine.error != null -> {
                    etaBig.text = NavEngine.error ?: "Plotting route…"; etaBig.setTextColor(ink); etaSmall.text = NavEngine.destinationName
                }
                else -> {
                    val delay = NavEngine.trafficDelaySeconds / 60
                    etaBig.text = NavEngine.arrivalClock()
                    etaBig.setTextColor(readable(when {
                        delay >= 10 -> Color.rgb(255, 110, 110)
                        delay >= 3 -> Color.rgb(255, 184, 107)
                        else -> Color.rgb(123, 227, 160)
                    }))
                    etaTraffic.text = if (delay >= 1) "+$delay min traffic" else ""
                    etaTraffic.setTextColor(readable(Color.rgb(255, 140, 90)))
                    etaSmall.text = "${NavEngine.formatDuration(NavEngine.remainingSeconds)} · ${NavEngine.formatDistance(NavEngine.remainingDistance)} · ${NavEngine.destinationName}"
                    renderStops()
                    // Share always stays; it shrinks to its icon in a convoy (room for 👥), with big text
                    // or in Greek (longer words), so the arrival time keeps its room
                    convoyBtn.visibility = if (Convoy.active) View.VISIBLE else View.GONE
                    // only when something changed: new drawables every refresh (once a second in a
                    // convoy) made the Share and convoy buttons flicker
                    val btnKey = "${Convoy.active}|${Convoy.members.size}|${LiveShare.active}|${L.greek}|${theme.name}|${MapState.dark}|${resources.configuration.fontScale}"
                    if (btnKey != shareBtnKey) { shareBtnKey = btnKey
                    if (Convoy.active || resources.configuration.fontScale > 1.1f || L.greek) {
                        shareBtn.text = if (LiveShare.active) "●" else ""
                        shareBtn.contentDescription = "Share your trip"
                        val ic = getDrawable(R.drawable.ic_act_share)?.mutate()?.apply {
                            setTint(ink); setBounds(0, 0, (22 * d).toInt(), (22 * d).toInt())
                        }
                        shareBtn.setCompoundDrawables(ic, null, null, null)
                    } else {
                        shareBtn.setCompoundDrawables(null, null, null, null)
                        shareBtn.text = if (LiveShare.active) "● Live" else "Share"
                    }
                    if (Convoy.active) {
                        convoyBtn.text = "👥 ${Convoy.members.size + 1}"
                        convoyBtn.contentDescription = "Convoy: ${Convoy.members.size + 1} drivers"
                        convoyBtn.setTextColor(readable(accent)); convoyBtn.background = bg(Color.TRANSPARENT, accent, 1.5f, 22f)
                    }
                    }
                }
            }
        }
        updateMapInsets()
    }

    private var shareBtnKey = ""
    private val quietTick = Runnable { refreshChrome() }
    private var laterShowing = false

    /** What waited while you drove, one list. */
    private fun showLater() {
        if (laterShowing || isFinishing) return
        val items = Later.take()
        if (items.isEmpty()) return
        laterShowing = true
        GameBuilder(this@MainActivity).setTitle(L.t("While you were driving"))
            .setMessage(items.joinToString("\n\n"))
            .setPositiveButton("OK", null)
            .setOnDismissListener { laterShowing = false; refreshChrome() }
            .showT()
    }

    private fun updateMapInsets() {
        val top = when {
            offerCard.visibility == View.VISIBLE -> offerCard.bottom
            searchCard.visibility == View.VISIBLE -> topArea.bottom.takeIf { suggestScroll.visibility != View.VISIBLE }
                ?: (searchCard.bottom + topArea.paddingTop)
            else -> insets.top
        }
        val bottom = when {
            placeCard.visibility == View.VISIBLE -> placeCard.height
            preview.visibility == View.VISIBLE -> preview.height
            navCard.visibility == View.VISIBLE -> navCard.height
            homeSheet.visibility == View.VISIBLE -> (homeSheet.height - homeSheet.translationY).toInt()
            else -> insets.bottom
        }
        val styleUp = if (styleBtn.visibility == View.VISIBLE) 60 * d else 0f
        styleBtn.translationY = -(bottom + 14 * d)
        recenterBtn.translationY = -(bottom + 14 * d + styleUp)
        val recUp = if (recenterBtn.visibility == View.VISIBLE) 70 * d else 0f
        laterPill.translationY = -(bottom + 14 * d + styleUp + recUp) // above the buttons, clear of the speed
        if (map.insetTop != top || map.insetBottom != bottom) {
            map.insetTop = top
            map.insetBottom = bottom
            map.invalidate()
        }
    }

    // ------------------------------------------------------------------ suggestions

    private fun showSuggestions() {
        if (search.text.trim().length < 2) showRecents() else renderResults(lastResults, "Searching…")
    }

    private fun showRecents() {
        val seq = ++querySeq
        lastResults = emptyList()
        val fav = Places.savedPlaces()
        val rec = Places.recents().filter { r -> fav.none { Geo.distance(it.pos, r.pos) < 25 } }
        if (fav.isNotEmpty()) renderSections(listOf("SAVED" to fav.take(6), "RECENT" to rec.take(4)))
        else renderResults(rec, if (rec.isEmpty()) "Type a place, address or business" else null, header = if (rec.isEmpty()) null else "RECENT", force = true)
        val near = LocationTracker.position ?: return
        Places.warmUp(near, force = true)
        io.execute {
            val around = try { Places.around(near) } catch (e: Exception) { emptyList() }
            if (around.isNotEmpty()) runOnUiThread {
                if (seq != querySeq || isFinishing) return@runOnUiThread
                renderSections(listOfNotNull(("SAVED" to fav.take(6)).takeIf { fav.isNotEmpty() }, "RECENT" to rec.take(4), "AROUND YOU" to around))
            }
        }
    }

    /** Several titled lists in the suggestions box. */
    private fun renderSections(sections: List<Pair<String, List<Place>>>) {
        val p = (12 * d).toInt()
        suggestList.removeAllViews()
        for ((title, places) in sections) {
            if (places.isEmpty()) continue
            suggestList.addView(TextView(this).apply {
                text = title; textSize = 12f; setTextColor(dim(170)); letterSpacing = 0.1f
                setPadding(p + p / 2, p, p, p / 3)
            })
            for (pl in places) suggestList.addView(resultRow(pl, removable = title == "RECENT"))
        }
        suggestScroll.visibility = View.VISIBLE
    }

    private fun runQuery(q: String, full: Boolean) {
        if (q.trim().length < 2) return
        val seq = ++querySeq
        val near = LocationTracker.position
        if (full) hideKeyboard()
        io.execute {
            // 1) instant: names in the map data on the phone
            val local = try { Places.local(q, near) } catch (e: Exception) { emptyList<Place>() }
            if (local.isNotEmpty()) runOnUiThread {
                if (seq == querySeq && !isFinishing) { lastResults = local; renderResults(local, null, header = "NEARBY") }
            }
            // 2) the exact address and the search services, merged with the local names
            val r = Places.find(q, near, full, local)
            runOnUiThread {
                if (seq != querySeq || isFinishing) return@runOnUiThread
                val merged = r.orEmpty()
                lastResults = merged
                val notHere = Places.missingWord(q, merged)
                renderResults(merged, when {
                    notHere != null -> L.t("No “$notHere” on the map here. Other matches:")
                    merged.isNotEmpty() -> null
                    r == null -> "No connection: check mobile data"
                    else -> "Nothing found"
                }, missing = if (r != null && pickFor == null) q.trim() else null)
            }
        }
    }

    /** Typed in search before "Can't find it?": the label for the pin you drop next. */
    private var pinName: String? = null

    private fun renderResults(places: List<Place>, message: String?, header: String? = null, force: Boolean = false, missing: String? = null) {
        if (!force && !search.hasFocus() && search.text.isEmpty() && places.isEmpty() && message == null) return
        val p = (12 * d).toInt()
        suggestList.removeAllViews()
        (header ?: pickFor?.let { when (it) { "home" -> "SET HOME"; "work" -> "SET WORK"; else -> "CONVOY DESTINATION" } })?.let {
            suggestList.addView(TextView(this).apply {
                text = it; textSize = 12f; setTextColor(dim(170)); letterSpacing = 0.1f
                setPadding(p + p / 2, p, p, p / 3)
            })
        }
        message?.let {
            suggestList.addView(TextView(this).apply { text = it; textSize = 15f; setTextColor(dim(170)); setPadding(p + p / 2, p, p, p) })
        }
        val near = LocationTracker.position
        // typed a pharmacy word: the ones on duty now on top, then everything else
        if (pickFor == null && header != "RECENT" && near != null && Duties.isPharmacyQuery(search.text.toString())) {
            val now = Duties.now()
            val on = Duties.onDutyNear(near, 15_000.0, now).take(3)
            if (on.isNotEmpty()) {
                val moon = readable(PoiLayer.MOON)
                suggestList.addView(label("🌙 " + L.t("ON DUTY NOW"), 12f, bold = true).apply { setTextColor(moon); letterSpacing = 0.1f; setPadding(p + p / 2, p, p, p / 3) })
                for (dt in on) suggestList.addView(pharmacyRow(Duties.place(dt, now), "🌙 " + L.t("On duty") + " · " + Duties.till(dt, now), moon, dt.phone))
                suggestList.addView(label(L.t("All pharmacies nearby") + " ›", 15f, bold = true).apply {
                    setTextColor(moon); setPadding(p + p / 2, p / 2, p, p / 2); isClickable = true
                    setOnClickListener { hideKeyboard(); showPharmacies() }
                })
                if (places.isNotEmpty()) suggestList.addView(label(L.t("PLACES"), 12f, bold = true).apply { setTextColor(dim(170)); letterSpacing = 0.1f; setPadding(p + p / 2, p, p, p / 3) })
            }
        }
        for (pl in places) suggestList.addView(resultRow(pl, removable = header == "RECENT"))
        // not there (or not the right one): put it on the map yourself, saved under that name
        if (missing != null) suggestList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p, p + p / 2, p); isClickable = true
            addView(label("📍", 20f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams((40 * d).toInt(), WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Can't find it?", 15f, bold = true).apply { setTextColor(readable(accent)) })
                addView(label("Hold your finger on the map where it is, and save it as \"$missing\"", 13f).apply { setTextColor(dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = p / 2 })
            setOnClickListener {
                pinName = missing
                search.setText(""); search.clearFocus(); hideKeyboard(); suggestScroll.visibility = View.GONE; refreshChrome()
                Toast.makeText(this@MainActivity, L.t("Hold your finger on the map where it is"), Toast.LENGTH_LONG).show()
            }
        })
        suggestScroll.visibility = View.VISIBLE
        refreshChrome()
        // keep the list from covering the whole screen
        suggestScroll.post {
            val max = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            val lp = suggestScroll.layoutParams
            val want = if (suggestList.height > max) max else WRAP_CONTENT
            if (lp.height != want) { lp.height = want; suggestScroll.layoutParams = lp }
        }
    }

    private fun resultRow(pl: Place, removable: Boolean = false): View {
        val p = (12 * d).toInt()
        val near = LocationTracker.position
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p - p / 4, p + p / 2, p - p / 4)
            isClickable = true
            setOnClickListener { choose(pl) }
        }
        row.addView(TextView(this).apply {
            text = pl.icon; textSize = 20f; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), WRAP_CONTENT)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p / 2, 0, p / 2, 0)
            addView(TextView(context).apply {
                text = pl.name; textSize = 16f; setTextColor(ink); typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            if (pl.address.isNotBlank()) addView(TextView(context).apply {
                text = pl.address; textSize = 13f; setTextColor(dim(170))
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            val stars = Reviews.stars(pl.rating, pl.ratings)
            if (pl.extra.isNotBlank() || stars != null) addView(TextView(context).apply {
                text = listOfNotNull(stars, pl.extra.takeIf { it.isNotBlank() }).joinToString("  ")
                textSize = 13f; setTextColor(readable(accent)); typeface = Typeface.DEFAULT_BOLD
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (near != null) row.addView(TextView(this).apply {
            text = NavEngine.formatDistance(Geo.distance(near, pl.pos)); textSize = 13f; setTextColor(dim(170))
        })
        if (!removable && pl.icon != "🛣" && pl.icon != "🏙") {
            val saved = Places.isSaved(pl)
            row.addView(TextView(this).apply {
                text = if (saved) "★" else "☆"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(if (saved) readable(accent) else dim(170))
                contentDescription = if (saved) "Saved: edit" else "Save this place"
                setOnClickListener { showSaveDialog(pl) { text = if (Places.isSaved(pl)) "★" else "☆"; setTextColor(if (Places.isSaved(pl)) readable(accent) else dim(170)) } }
            }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        }
        if (removable) row.addView(removeX("Remove from recent") {
            Places.removeRecent(pl)
            row.animate().alpha(0f).translationX(row.width * 0.3f).setDuration(animMs(180)).withEndAction { showRecents() }.start()
        })
        return row
    }

    /** A round ✕ that takes something off a list. */
    private fun removeX(desc: String, onClick: () -> Unit) = TextView(this).apply {
        text = "✕"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(dim(200))
        contentDescription = desc
        background = bg(dim(26), radius = 16f, pill = true)
        layoutParams = LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()).apply { marginStart = (10 * d).toInt() }
        setOnClickListener { onClick() }
    }

    private fun choose(pl: Place) {
        val key = pickFor
        closeSearch()
        search.setText("")
        if (key == "convoy") {
            val wasOnIt = Convoy.onConvoyRoute
            Convoy.setDestination(pl)
            if (wasOnIt) { NavEngine.start(this, pl); MapState.recenter() }
            openSheet("convoy")
        } else if (key != null) {
            Places.save(key, pl)
            Toast.makeText(this, L.t((if (key == "home") "Home" else "Work") + " saved: ${pl.name}"), Toast.LENGTH_SHORT).show()
            etaAskedAt = 0
            refreshChrome()
        } else {
            NavEngine.plan(this, pl)
        }
    }

    private fun closeSearch() {
        querySeq++
        fuelShown = null; Fuel.pins = emptyList()
        pickFor = null
        suggestScroll.visibility = View.GONE
        search.clearFocus()
        hideKeyboard()
        refreshChrome()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ------------------------------------------------------------------ bottom sheets

    /** Where the open sheet was opened from (You › Settings › …): its back button goes there. */
    private val sheetStack = ArrayList<String>()
    private var sheetNoPush = false

    private fun sheetTitle(kind: String) = when (kind) {
        "you" -> "You"; "settings" -> "Settings"; "more" -> "More"; "convoy", "friends", "addfriend" -> "Together"
        "progress" -> "Progress"; "garage" -> "Garage"; "trips" -> "Trips"; "recap" -> "Your recap"; "style" -> "Map style"; "offline" -> "Offline maps"
        "convoyinvite" -> "Invite"; else -> "Back"
    }

    /** Back one step (to the sheet this one came from), or closed if it was the first. */
    private fun sheetBack() {
        if (sheetKind == "settings" && settingsPage != null) { settingsPage = null; refreshChrome(); return }
        val prev = sheetStack.removeLastOrNull() ?: run { closeSheet(); return }
        sheetNoPush = true
        try { openSheet(prev) } finally { sheetNoPush = false }
    }

    /** The same sheet group (Together's two tabs): no step back in between. */
    private fun switchSheet(kind: String) { sheetNoPush = true; try { openSheet(kind) } finally { sheetNoPush = false } }

    private fun openSheet(kind: String) {
        // opened from another sheet: remember it, for the back button
        if (!sheetNoPush && sheetOverlay.visibility == View.VISIBLE && sheetKind.isNotEmpty() && sheetKind != kind) {
            if (sheetStack.lastOrNull() != sheetKind) sheetStack += sheetKind
            if (sheetStack.size > 6) sheetStack.removeAt(0)
        }
        if (sheetOverlay.visibility != View.VISIBLE) sheetStack.clear()
        if (kind != sheetKind) { settingsPage = null; settingsQuery = "" }
        if (kind != sheetKind) styleScroll = 0
        // a sheet opened from another sheet (e.g. Settings › Convoy) must draw itself, not keep the old one's views
        quickKey = ""; convoyKey = ""; offlineKey = ""; tripsKey = ""; friendsKey = ""; moreKey = ""; inviteKey = ""; progressKey = ""; garageKey = ""; youKey = ""; styleKey = ""; settingsKey = ""; reportKey = ""
        if ((kind == "friends" || kind == "addfriend") && Friends.ready && Friends.core.started) Friends.kick()
        sheetKind = kind
        if (sheetOverlay.visibility != View.VISIBLE) {
            // the sheet slides up over a fading-in shade
            sheetOverlay.alpha = 0f; sheetOverlay.visibility = View.VISIBLE
            sheetOverlay.animate().alpha(1f).setDuration(animMs(160)).start()
            sheetHost.translationY = 60 * d
            sheetHost.animate().translationY(0f).setDuration(animMs(240)).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
        }
        refreshChrome()
        if (kind == "fuelfilters") renderFuelFilters()
    }

    private fun closeSheet() {
        sheetStack.clear()
        sheetOverlay.visibility = View.GONE
        sheetKind = ""
        sheetHost.removeAllViews()
    }

    /** "‹ You": back to where this sheet was opened from. */
    private fun backPill(to: String, onClick: () -> Unit) = TextView(this).apply {
        text = "‹ " + L.t(to); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; maxLines = 1
        setTextColor(readable(accent)); background = bg(dim(26), radius = 12f)
        val q = (10 * d).toInt(); setPadding(q, q / 2, q + q / 3, q / 2); minHeight = (40 * d).toInt()
        contentDescription = L.t("Back to $to")
        setOnClickListener { onClick() }
    }

    /** A sheet's title, centred, with the way back on the left and ✕ on the right. */
    private fun sheetHeader(title: String, back: Pair<String, () -> Unit>? = sheetStack.lastOrNull()?.let { from -> sheetTitle(from) to { sheetBack() } }) = FrameLayout(this).apply {
        val side = if (back != null) (104 * d).toInt() else (52 * d).toInt()
        addView(label(title, 22f, bold = true).apply {
            setTextColor(ink); typeface = theme.typeface; gravity = Gravity.CENTER; maxLines = 1
            setPadding(side, 0, side, 0)
            // a long title next to the back button shrinks to fit instead of being cut
            setAutoSizeTextTypeUniformWithConfiguration(14, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, FrameLayout.LayoutParams(MATCH_PARENT, (44 * d * maxOf(1f, resources.configuration.fontScale)).toInt(), Gravity.CENTER_VERTICAL))
        back?.let { (to, go) -> addView(backPill(to, go), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL)) }
        addView(pill("✕") { closeSheet() }.apply { setTextColor(ink) }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
    }

    /** Shows a card with a short slide up and fade in (only when it wasn't showing already). */
    private fun appear(v: View, from: Float = 36f) {
        if (v.visibility == View.VISIBLE) return
        v.visibility = View.VISIBLE
        v.alpha = 0f; v.translationY = from * d
        v.animate().alpha(1f).translationY(0f).setDuration(animMs(200)).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }

    internal fun showStylePicker() = openSheet("style")

    private fun renderStylePicker() {
        // only when something it shows changed: rebuilt on every map or GPS change, the sheet
        // flickered on slower phones (all the previews laid out again, many times a second)
        val key = "${theme.name}|${MapState.theme}|${MapState.dark}|${MapState.lightMode}|${MapState.carTheme}|${L.greek}"
        if (key == styleKey && sheetHost.childCount > 0) return
        styleKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 24f, topOnly = true)
        sheetHost.addView(sheetHeader("Map style"))
        // Auto / Light / Dark
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = (4 * d).toInt(); setPadding(p, p, p, p)
            background = bg(Color.argb(30, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f)
        }
        for (m in MapState.LightMode.entries) {
            val sel = MapState.lightMode == m
            seg.addView(TextView(this).apply {
                text = when (m) { MapState.LightMode.AUTO -> "Auto"; MapState.LightMode.LIGHT -> "Light"; MapState.LightMode.DARK -> "Dark" }
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(if (sel) onAccent(accent) else ink)
                if (sel) background = bg(accent, radius = 11f)
                setOnClickListener { MapState.setLightMode(m) }
            }, LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f))
        }
        sheetHost.addView(seg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        if (MapState.lightMode == MapState.LightMode.AUTO) sheetHost.addView(label("Switches at sunrise and sunset", 13f).apply {
            setTextColor(dim()); setPadding((4 * d).toInt(), (6 * d).toInt(), 0, 0)
        })
        // theme grid, 3 per row: scrolls, so every style (and the car's style below) can be reached
        // on short screens and with big text
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.66).apply { addView(grid); post { scrollTo(0, styleScroll) }
            setOnScrollChangeListener { _, _, y, _, _ -> styleScroll = y } }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val dark = MapState.dark
        var row: LinearLayout? = null
        Theme.entries.forEachIndexed { i, th ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
            }
            val sel = th == MapState.theme
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (3 * d).toInt(); setPadding(p, p, p, p)
                background = bg(Color.argb(24, Color.red(ink), Color.green(ink), Color.blue(ink)), if (sel) accent else dim(50), if (sel) 3f else 1f, 14f)
                setOnClickListener { MapState.setTheme(th) }
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val id = resources.getIdentifier("thumb_${th.name.lowercase()}_${if (dark) "d" else "l"}", "drawable", packageName)
                if (id != 0) setImageResource(id) else setBackgroundColor(th.lookFor(dark).style.land)
                clipToOutline = true
            }
            card.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, (88 * d).toInt()))
            card.addView(label(th.title, 12f, bold = true).apply {
                setTextColor(ink); setPadding((6 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (4 * d).toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            row?.addView(card, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i % 3 != 2) marginEnd = (8 * d).toInt()
            })
        }
        // fill an incomplete last row so cards keep their width
        val rest = (3 - Theme.entries.size % 3) % 3
        repeat(rest) { row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
        // car style
        grid.addView(settingRow("Android Auto style", MapState.carTheme?.title ?: "Same as phone") { chooseCarStyle() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt(); bottomMargin = (12 * d).toInt() })
    }

    /** Where the style list was scrolled to (picking a style redraws the sheet: it stays put). */
    private var styleScroll = 0

    private fun chooseCarStyle() {
        val names = arrayOf("Same as phone") + Theme.entries.map { it.title }.toTypedArray()
        val cur = MapState.carTheme?.let { it.ordinal + 1 } ?: 0
        GameBuilder(this@MainActivity).setTitle("Android Auto style")
            .setSingleChoiceItems(names, cur) { dlg, i ->
                MapState.setCarTheme(if (i == 0) null else Theme.entries[i - 1]); dlg.dismiss()
            }.showT()
    }

    private fun settingRow(title: String, value: String?, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (52 * d).toInt()
        val p = (14 * d).toInt(); setPadding(p, 0, p, 0)
        setOnClickListener { onClick() }
        // a long value goes under the title (side by side it squeezed the title to a letter a line)
        if (value != null && value.length > 24) {
            val p2 = (10 * d).toInt(); setPadding(p, p2, p, p2)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 15f).apply { setTextColor(ink) })
                addView(label(value, 13f).apply { setTextColor(dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("›", 14f).apply { setTextColor(dim()) })
            return@apply
        }
        addView(label(title, 15f).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (value != null) addView(label("$value  ›", 14f).apply { setTextColor(dim()) })
    }

    private fun switchRow(title: String, on: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (52 * d).toInt()
        val p = (14 * d).toInt(); setPadding(p, 0, p / 2, 0)
        addView(label(title, 15f).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val sw = android.widget.Switch(context).apply {
            isChecked = on
            val onCol = Ui.onPanel(theme, accent, 3.0)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            // off = plain grey, on = the theme's colour: easy to tell apart in every theme
            val lightP = Ui.luminance(panel) > 0.4
            val offThumb = if (lightP) Color.rgb(250, 250, 250) else Color.rgb(185, 185, 185)
            val offTrack = if (lightP) Color.argb(120, 0, 0, 0) else Color.argb(110, 255, 255, 255)
            thumbTintList = android.content.res.ColorStateList(states, intArrayOf(onCol, offThumb))
            trackTintList = android.content.res.ColorStateList(states, intArrayOf(
                Color.argb(150, Color.red(onCol), Color.green(onCol), Color.blue(onCol)), offTrack))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    private fun group(title: String, vararg rows: View): List<View> {
        val head = label(title.uppercase(), 12f, bold = true).apply {
            setTextColor(dim(170)); letterSpacing = 0.08f; setPadding((6 * d).toInt(), (14 * d).toInt(), 0, (6 * d).toInt())
            if (title.isBlank()) { text = ""; setPadding(0, (6 * d).toInt(), 0, 0) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(Color.argb(26, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f)
            rows.forEachIndexed { i, r ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(dim(40)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
                addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
        return listOf(head, box)
    }

    private fun renderReport() {
        val key = "${theme.name}|${MapState.dark}|${L.greek}"
        if (key == reportKey && sheetHost.childCount > 0) return
        reportKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 24f, topOnly = true)
        sheetHost.addView(sheetHeader("Report"))
        // marking a camera works without the shared database: it's kept on this phone
        sheetHost.addView(label("CAMERA HERE (saved on this phone)", 12f, bold = true).apply {
            setTextColor(dim()); letterSpacing = 0.06f; setPadding((4 * d).toInt(), (4 * d).toInt(), 0, (6 * d).toInt())
        })
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((k, glyph, title) in listOf(Triple("speed", "📷", "Speed camera"), Triple("redlight", "🚦", "Red-light camera"))) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                    minimumHeight = (60 * d).toInt()
                    background = bg(Color.argb(28, Color.red(ink), Color.green(ink), Color.blue(ink)), dim(60), 1f, 16f)
                    addView(label(glyph, 24f)); addView(label("  $title", 15f, bold = true).apply { setTextColor(ink) })
                    setOnClickListener {
                        val ok = Alerts.markCamera(k)
                        Toast.makeText(this@MainActivity, L.t(if (ok) "$title marked: you'll be warned here" else "Waiting for your location"), Toast.LENGTH_SHORT).show()
                        if (ok) closeSheet()
                    }
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (k == "speed") marginEnd = (10 * d).toInt() })
            }
        })
        // road reports shared with other drivers: off until GameRoute has its own server
        if (!Alerts.reportsReady) {
            sheetHost.addView(label("Marked cameras stay on this phone and warn you every time you pass.", 13f).apply {
                setTextColor(dim()); setPadding((4 * d).toInt(), (12 * d).toInt(), 0, (4 * d).toInt())
            })
            return
        }
        sheetHost.addView(label("DRIVER REPORTS", 12f, bold = true).apply {
            setTextColor(dim()); letterSpacing = 0.06f; setPadding((4 * d).toInt(), (16 * d).toInt(), 0, 0)
        })
        if (LocationTracker.position == null) {
            sheetHost.addView(label("Waiting for your location…", 15f).apply { setTextColor(dim()) })
        }
        var row: LinearLayout? = null
        Alerts.reportTypes.forEachIndexed { i, rt ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                sheetHost.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = (96 * d).toInt()
                background = bg(Color.argb(28, Color.red(ink), Color.green(ink), Color.blue(ink)), dim(60), 1f, 16f)
                setOnClickListener {
                    Alerts.report(rt.key) { ok ->
                        Toast.makeText(this@MainActivity, L.t(if (ok) "Thanks! ${rt.title} reported" else "Couldn't send the report: check internet"), Toast.LENGTH_SHORT).show()
                    }
                    closeSheet()
                }
            }
            tile.addView(label(rt.glyph, 30f).apply { gravity = Gravity.CENTER })
            tile.addView(label(rt.title, 14f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER })
            row?.addView(tile, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i % 3 != 2) marginEnd = (10 * d).toInt() })
        }
        sheetHost.addView(label("Reports show for other drivers for an hour, longer if they confirm them.", 13f).apply {
            setTextColor(dim()); setPadding((4 * d).toInt(), (12 * d).toInt(), 0, 0)
        })
    }

    /** ☰ while driving: the things that used to be separate round buttons, plus Settings. */
    private var quickKey = ""
    private var styleKey = ""
    private var settingsKey = ""
    private var reportKey = ""

    /**
     * Everything the settings pages show, in one number: every saved setting (friends' busy data
     * left out: it changes every few minutes and isn't shown there) and the switches kept in memory.
     */
    private fun settingsState(): Int {
        var h = 17
        val dir = java.io.File(applicationInfo.dataDir, "shared_prefs")
        for (f in dir.list().orEmpty().sorted()) {
            val name = f.removeSuffix(".xml")
            if (name == "friends") continue
            h = h * 31 + getSharedPreferences(name, MODE_PRIVATE).all.hashCode()
        }
        h = h * 31 + "${Voice.muted}${Traffic.showLayer}${Perf.mode}${Perf.effects}${Perf.animations}${Perf.smoothness}${Perf.liveFriends}${Perf.saveGpsStopped}${Offline.downloading}".hashCode()
        return h
    }

    private fun renderQuick() {
        // rebuilt only when something on it changes (not on every convoy update)
        val key = "${Convoy.active}|${Convoy.members.size}|${NavEngine.route.size > 1}|${theme.name}|${MapState.dark}|${Traffic.showLayer}"
        if (key == quickKey && sheetHost.childCount > 0) return
        quickKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 24f, topOnly = true)
        sheetHost.addView(sheetHeader("While driving"))
        val rows = listOfNotNull(
            if (Convoy.active) settingRow("Leave convoy", "${Convoy.members.size + 1} drivers") { confirmLeaveConvoy() } else null,
            if (NavEngine.route.size > 1) settingRow("Route overview", null) {
                closeSheet()
                val r = NavEngine.route
                MapState.phone.showOverview(r.filterIndexed { i, _ -> i % 5 == 0 } + r.last())
            } else null,
            settingRow("Map style", theme.title) { openSheet("style") },
            settingRow("Save my parking spot here", null) {
                val here = LocationTracker.position
                if (here == null) Toast.makeText(this, L.t("Waiting for your location…"), Toast.LENGTH_SHORT).show()
                else { Parked.save(here); Toast.makeText(this, L.t("Parking spot saved"), Toast.LENGTH_SHORT).show(); closeSheet() }
            },
            settingRow("Settings", null) { openSheet("settings") },
        )
        group("", *rows.toTypedArray()).forEach { sheetHost.addView(it) }
    }

    internal fun showSettings() = openSheet("settings")

    /** Settings › Performance › Animations: Full as designed, Reduced quicker, Off none. */
    private fun animMs(ms: Long): Long = when (Perf.anim) { Perf.FULL -> ms; Perf.REDUCED -> ms / 2; else -> 0L }
    internal fun openReportForTest() = openSheet("report")
    internal fun openSheetForTest(kind: String) = openSheet(kind)
    /** Tests: the settings pages (null = the main list) and their keys. */
    internal fun settingsPageForTest(page: String?) { settingsPage = page; refreshChrome() }
    internal fun settingsKeysForTest(): List<String> = settingsGroups().map { it.key }
    internal fun refreshForTest() { lastHomeKey = ""; refreshChrome() }

    /** Which settings page is open: null = the list of groups. */
    private var settingsPage: String? = null

    private class SettingsGroup(val key: String, val title: String, val summary: String, val icon: Int, val tint: Int)

    private fun settingsGroups() = listOf(
        SettingsGroup("map", "Map", "Style, light or dark, Android Auto style, places, offline maps", R.drawable.ic_set_map, Color.rgb(59, 91, 219)),
        SettingsGroup("nav", "Navigation", "Voice, faster routes, tolls and motorways, live traffic", R.drawable.ic_set_nav, Color.rgb(30, 158, 90)),
        SettingsGroup("alerts", "Alerts", "Speed and red-light cameras, accidents, cameras you marked", R.drawable.ic_set_alert, Color.rgb(194, 65, 12)),
        SettingsGroup("sharing", "Sharing", "Your name, convoy, live arrival", R.drawable.ic_set_share, Color.rgb(124, 58, 237)),
        SettingsGroup("app", "App", "Your progress, Garage, trip history, privacy, about" + if (Updater.ENABLED) ", updates" else "", R.drawable.ic_set_app, Color.rgb(13, 110, 97)),
        SettingsGroup("perf", "Performance", "Smooth or battery, map effects, animations, map smoothness", R.drawable.ic_set_perf, Color.rgb(202, 138, 4)),
        SettingsGroup("advanced", "Advanced", "Units, map links, star ratings and reviews, welcome screens", R.drawable.ic_set_adv, Color.rgb(100, 100, 110)),
    ) + (if (BuildConfig.TEST_BUILD) listOf(
        // test builds only (never in a public release): tools for trying things out
        SettingsGroup("dev", "Developer", "Test builds only: simulate drives, test friends, XP, celebrations, log", R.drawable.ic_set_adv, Color.rgb(219, 39, 119)),
    ) else emptyList())

    private fun renderSettings() {
        // typing in the search box: don't rebuild under the keyboard
        if (settingsPage == null && sheetHost.findFocus() is EditText) return
        // only when a setting (or the page, or the look) changed: see renderStylePicker
        val key = "$settingsPage|$settingsQuery|${theme.name}|${MapState.dark}|${L.greek}|${settingsState()}"
        if (key == settingsKey && sheetHost.childCount > 0) return
        settingsKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 24f, topOnly = true)
        val maxH = minOf((resources.displayMetrics.heightPixels * 0.72f).toInt(), (640 * d).toInt())
        val page = settingsPage
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (page == null) {
            sheetHost.addView(sheetHeader("Settings"))
            // search every setting by its name (English or Greek)
            val found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val groupsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            fun fill(q: String) {
                found.removeAllViews()
                val qq = q.trim().lowercase()
                groupsBox.visibility = if (qq.isEmpty()) View.VISIBLE else View.GONE
                if (qq.isEmpty()) return
                for (g in settingsGroups()) {
                    val hits = settingsRowsFor(g.key).filter { row ->
                        val t = (row as? ViewGroup)?.let { firstText(it) }.orEmpty()
                        t.lowercase().contains(qq) || L.t(t).lowercase().contains(qq)
                    }
                    if (hits.isEmpty()) continue
                    found.addView(label(L.t(g.title).uppercase(), 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding((4 * d).toInt(), (12 * d).toInt(), 0, (4 * d).toInt()) })
                    group("", *hits.toTypedArray()).forEach { found.addView(it) }
                }
                if (found.childCount == 0) found.addView(label(L.t("No setting called \"$q\""), 14f).apply { setTextColor(dim()); setPadding((4 * d).toInt(), (12 * d).toInt(), 0, 0) })
                L.translateTree(found); Icons.applyTree(found)
            }
            body.addView(EditText(this).apply {
                hint = L.t("Search settings"); setSingleLine(); textSize = 16f
                setTextColor(ink); setHintTextColor(dim(130))
                background = bg(dim(22), dim(80), 1f, 22f, pill = true)
                val q = (12 * d).toInt(); setPadding(q + q / 2, q, q, q)
                setText(settingsQuery)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: Editable?) { settingsQuery = e?.toString().orEmpty(); fill(settingsQuery) }
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
            body.addView(found)
            body.addView(groupsBox)
            for (g in settingsGroups()) groupsBox.addView(settingsEntry(g), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            fill(settingsQuery)
            body.addView(label("GameRoute build ${shownBuild()}", 12f).apply {
                setTextColor(dim(150)); gravity = Gravity.CENTER; setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            sheetHost.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        if (page == "whatsnew") {
            // What's new: a list, back to Advanced (no pop-up after updates; the NEW tag is enough)
            sheetHost.addView(sheetHeader("What's new", "Advanced" to { settingsPage = "advanced"; refreshChrome() }))
            fun heading(t: String) = body.addView(label(L.t(t).uppercase(), 12.5f, bold = true).apply {
                setTextColor(dim(150)); letterSpacing = 0.08f; setPadding((12 * d).toInt(), (16 * d).toInt(), 0, (6 * d).toInt())
            })
            fun line(b: String, en: String, el: String) = body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val q = (12 * d).toInt(); setPadding(q, q / 3, q, q / 3)
                addView(label(b, 14f, bold = true).apply { setTextColor(readable(accent)) }, LinearLayout.LayoutParams((44 * d).toInt(), WRAP_CONTENT))
                addView(label(if (L.greek) el else en, 14f).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // after 100: one line each, then 100 in full, then every earlier build in one line (no icons)
            val newer = History.builds.filter { (it.first.substringBefore('.').toIntOrNull() ?: 0) > 100 }
            if (newer.isNotEmpty()) { heading("Since build 100"); newer.forEach { line(it.first, it.second, it.third) } }
            heading("Build 100")
            for ((ic, t, sub) in WHATS_NEW) body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val q = (12 * d).toInt(); setPadding(q, q * 3 / 4, q, q * 3 / 4)
                addView(label(ic, 22f).apply { gravity = Gravity.CENTER; background = bg(dim(26), radius = 12f) }, LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { marginEnd = q })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(L.t(t), 16.5f, bold = true).apply { setTextColor(ink) })
                    addView(label(L.t(sub), 13.5f).apply { setTextColor(dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            heading("Earlier builds")
            History.builds.filter { (it.first.substringBefore('.').toIntOrNull() ?: 0) < 100 }.forEach { line(it.first, it.second, it.third) }
            prefs().edit().putBoolean("seenWhatsNew113", true).apply()
            sheetHost.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            body.post { (body.parent as? View)?.let { sv -> if (body.height > maxH) { sv.layoutParams.height = maxH; sv.requestLayout() } } }
            return
        }
        val g = settingsGroups().first { it.key == page }
        sheetHost.addView(sheetHeader(g.title, "Settings" to { settingsPage = null; refreshChrome() }))
        val rows = settingsRowsFor(page)
        group("", *rows.toTypedArray()).forEach { body.addView(it) }
        sheetHost.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { height = WRAP_CONTENT })
        body.post { (body.parent as? View)?.let { sv -> if (body.height > maxH) { sv.layoutParams.height = maxH; sv.requestLayout() } } }
    }

    private var settingsQuery = ""

    /** The first text in a settings row (its name). */
    private fun firstText(v: ViewGroup): String? {
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c is TextView && c.text.isNotBlank()) return c.text.toString()
            if (c is ViewGroup) firstText(c)?.let { return it }
        }
        return null
    }

    /** The rows of one settings page (also searched from the main page). */
    private fun settingsRowsFor(page: String): List<View> {
        val voice = if (Voice.muted) "Off" else if (Voice.lang == Phrases.GREEK) "Ελληνικά" else "English"
        return when (page) {
            "map" -> listOf(
                settingRow("Map style", theme.title) { openSheet("style") },
                settingRow("Light / dark", MapState.lightMode.label) { chooseLightMode() },
                settingRow("Android Auto style", MapState.carTheme?.title ?: "Same as phone") { chooseCarStyle() },
                switchRow("Car: dark map when headlights are on", MapState.followHeadlights) { MapState.followHeadlights = it },
                switchRow("Places on the map (fuel, parking, food…)", MapState.showPlaces) { MapState.showPlaces = it },
                switchRow("Show traffic (phone and car)", Traffic.showLayer) { Traffic.showLayer = it; MapState.changed.fire() },
                switchRow("The road you're on (under the arrow)", NavEngine.showRoadName) { NavEngine.showRoadName = it },
                settingRow("Offline maps", Offline.areas().let { a -> if (a.isEmpty()) "Regions, Wi-Fi only, updates" else "${a.size} · ${Offline.size(a.sumOf { it.bytes })}" }) { openSheet("offline") },
            )
            "dev" -> if (!BuildConfig.TEST_BUILD) emptyList() else listOf(
                settingRow("Play a level celebration", "Levels 5 to 50, in this style") {
                    val lv = (5..50 step 5).toList()
                    GameBuilder(this@MainActivity).setTitle("Play a level celebration")
                        // played over the Developer page: when it's done you're back here, not on the map
                        .setItems(lv.map { "Level $it" }.toTypedArray()) { _, i -> playCelebration(lv[i]) }.showT()
                },
                settingRow("Show a level card", "What each level unlocks") {
                    val lv = (2..50).toList()
                    GameBuilder(this@MainActivity).setTitle("Show a level card")
                        .setItems(lv.map { l -> "Level $l" + if (Xp.unlockedAt(l).isNotEmpty()) " · " + Xp.unlockedAt(l).joinToString { L.t(it.name) } else "" }.toTypedArray()) { _, i ->
                            showLevelUpCard(lv[i] - 1, lv[i], preview = true)
                        }.showT()
                },
                settingRow("What the app noted lately", "Reroute times, GPS, car screen") {
                    val notes = CrashLog.recentNotes().ifBlank { "Nothing yet." }
                    GameBuilder(this@MainActivity).setTitle("Lately").setMessage(notes)
                        .setNeutralButton("Copy") { _, _ ->
                            getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("GameRoute log", notes))
                            Toast.makeText(this, L.t("Copied"), Toast.LENGTH_SHORT).show()
                        }.setPositiveButton("OK", null).showT()
                },
                settingRow("Reset what's been seen", "Welcome screens, What's new, this level's celebration · the app reopens") {
                    // your current level celebrates again; the app reopens on the welcome screens
                    prefs().edit().putInt("levelCelebrated", (Explorer.level - 1).coerceAtLeast(1))
                        .remove("seenWhatsNew113").remove("welcomeDone").commit()
                    Toast.makeText(this, L.t("Reset"), Toast.LENGTH_SHORT).show()
                    closeSheet(); recreate()
                },
                settingRow("Test a crash", "Saves a report, like a real crash") {
                    GameBuilder(this@MainActivity).setTitle("Test a crash?").setMessage("The app closes and offers the report next time.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Crash") { _, _ -> throw RuntimeException("Test crash from the Developer page") }.showT()
                },
                // ---- driving, no car needed
                settingRow("Simulate a drive", if (Dev.simActive) "Driving now · tap to stop" else "Fake GPS to Home (or 2.5 km away) · earns nothing") {
                    if (Dev.simActive) { Dev.stopSim(); showSettings(); return@settingRow }
                    Dev.simulate(this, Places.saved("home")); closeSheet()
                },
                settingRow("While simulating…", "Leave the route, weak GPS, tunnel, no internet, offer, arrive") { devDriveTools() },
                settingRow("Fast forward", if (Dev.fastForward == 1) "Off · the road's speed" else "${Dev.fastForward}× faster · past the limits") { chooseFastForward() },
                // ---- friends and convoy, no friends needed
                settingRow("Add 4 test friends", "Driving, in a convoy, busy, offline · this phone only") {
                    Dev.addTestFriends(); Toast.makeText(this, "Test friends added", Toast.LENGTH_SHORT).show()
                },
                settingRow("Send me a test invite", "A convoy invite from Nikos (test)") {
                    Dev.testInvite(); Toast.makeText(this, "Invite on its way", Toast.LENGTH_SHORT).show()
                },
                settingRow("Start a test convoy", if (Dev.convoyOn) "On · tap to stop" else "You and 3 made-up drivers drive together, with quick messages") {
                    if (Dev.convoyOn) Dev.stopTestConvoy() else { Dev.startTestConvoy(this); closeSheet(); return@settingRow }; showSettings()
                },
                settingRow("Remove test friends", "And their invites") {
                    Dev.removeTestFriends(); Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show()
                },
                // ---- XP and levels (your real progress is backed up first)
                settingRow("Add XP", "Level ${Explorer.level} · ${Explorer.xp} XP") {
                    GameBuilder(this@MainActivity).setTitle("Add XP").setMessage(devBackupNote())
                        .setItems(arrayOf("+100 XP", "+1,000 XP")) { _, i -> devXp { Dev.addXp(this, if (i == 0) 100 else 1000) } }.showT()
                },
                settingRow("Set level", "1 to 50") {
                    val lv = (1..50).toList()
                    GameBuilder(this@MainActivity).setTitle("Set level")
                        .setItems(lv.map { "Level $it" }.toTypedArray()) { _, i -> devXp { Dev.setLevel(this, lv[i]) } }.showT()
                },
                settingRow("Try prestige", "Level 50, then prestige: back to level 1 with the next rank's ◆") {
                    GameBuilder(this@MainActivity).setTitle("Try prestige?").setMessage(devBackupNote() + "\n\nYou go to level 50 and prestige straight away: level 1, the next rank, its rewards.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Prestige") { _, _ ->
                            Dev.setLevel(this, 50); Xp.doPrestige()
                            prefs().edit().putInt("levelCelebrated", Explorer.level).apply()
                            progressKey = ""; showSettings(); showPrestigeReveal()
                        }.showT()
                },
                settingRow("Restore my real progress", if (Dev.hasBackup(this)) "Backed up before testing · the app restarts" else "Nothing to restore: no XP tools used yet") {
                    if (!Dev.hasBackup(this)) return@settingRow
                    GameBuilder(this@MainActivity).setTitle("Restore your real progress?").setMessage("XP and levels go back to how they were before testing. The app restarts.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Restore") { _, _ ->
                            if (Dev.restoreBackup(this)) {
                                val again = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                if (again != null) startActivity(again)
                                finishAffinity(); Runtime.getRuntime().exit(0)
                            }
                        }.showT()
                },
                settingRow("This build", "build ${shownBuild()} · test") { },
            )
            "nav" -> listOf(
                settingRow("Spoken directions", voice) { chooseVoice() },
                settingRow("How much the voice talks", L.t(TALK[Voice.talk])) { chooseTalk() },
                settingRow("Faster routes", if (NavEngine.autoSwitch) "Switch automatically" else "Ask me") { chooseAutoSwitch() },
                switchRow("Avoid tolls", NavEngine.avoidTolls) { NavEngine.avoidTolls = it },
                switchRow("Avoid motorways", NavEngine.avoidMotorways) { NavEngine.avoidMotorways = it },
                switchRow("Live traffic", Traffic.enabled) { Traffic.setEnabled(it) },
            )
            "alerts" -> listOf(
                switchRow("Speed cameras", Alerts.camerasOn) { Alerts.camerasOn = it },
                switchRow("Red light cameras", Alerts.redLightOn) { Alerts.redLightOn = it },
                switchRow("Accidents & roadworks", Alerts.incidentsOn) { Alerts.incidentsOn = it },
                settingRow("Cameras you marked", "${Alerts.myCameras().size}") {
                    val n = Alerts.myCameras().size
                    GameBuilder(this@MainActivity).setTitle("Cameras you marked")
                        .setMessage(if (n == 0) "None yet. While driving, tap ⚠ › Speed camera or Red-light camera to mark one where you are."
                            else "$n camera${if (n == 1) "" else "s"} saved on this phone. GameRoute warns you before each one.")
                        .apply { if (n > 0) setNeutralButton("Delete all") { _, _ -> Alerts.clearMyCameras(); showSettings() } }
                        .setPositiveButton("OK", null).showT()
                },
            )
            "sharing" -> listOf(
                settingRow("Your name in convoys", Convoy.myName.ifBlank { "Not set" }) { editConvoyName { showSettings() } },
                settingRow("Convoy", if (Convoy.active) "On · ${Convoy.members.size + 1} drivers" else "Start or join") { openSheet("convoy") },
                settingRow("Friends", if (Friends.core.started) L.t("${Friends.core.friends.size} friends") else L.t("Add friends")) { openSheet("friends") },
                switchRow("Show my level to friends", Xp.showToFriends) { Xp.showToFriends = it; Friends.Presence.refresh() },
            ) + (if (!Friends.core.started) emptyList() else listOf(
                settingRow("Your status", statusName(Friends.core.status)) { chooseStatus() },
                switchRow("Friends can invite me to convoys", Friends.core.allowInvites) { Friends.setAllowInvites(it) },
                settingRow("My friend code", Friends.core.code) {
                    getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Friend code", Friends.core.code))
                    Toast.makeText(this, L.t("Code copied"), Toast.LENGTH_SHORT).show()
                },
                settingRow("Make a new friend code", null) {
                    GameBuilder(this@MainActivity).setTitle("Make a new friend code?").setMessage("Your friends stay. Old links and QR codes stop working.")
                        .setPositiveButton("Make a new code") { _, _ -> Friends.newCode(); main.postDelayed({ showSettings() }, 300) }
                        .setNegativeButton("Cancel", null).showT()
                },
                settingRow("Blocked", "${Friends.core.blocked.size}") { showBlocked() },
            ))
            "app" -> listOfNotNull(
                settingRow("Trip history", "${Explorer.history().size} trips") { openSheet("trips") },
                settingRow("Your progress", "${Xp.stars}${L.t(Xp.title())} · " + L.t("level ${Explorer.level}")) { openSheet("progress") },
                settingRow("Garage", L.t("${Xp.items.count { it.unlocked }} of ${Xp.items.size} unlocked")) { openSheet("garage") },
                if (Updater.ENABLED) settingRow("Check for updates", "build ${shownBuild()}") { checkForUpdates(manual = true) } else null,
                settingRow("Backup & restore", L.t("Keep your progress in a file you save")) { chooseBackup() },
                settingRow("Language", L.label) { chooseLanguage() },
                settingRow("Privacy policy", null) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://vyto-line.github.io/GameRoute/privacy/"))) } catch (_: Exception) {}
                },
                settingRow("About & map credits", null) { showAbout() },
            )
            "perf" -> listOfNotNull(
                settingRow("Mode", L.t(PERF_MODE[Perf.mode]) + if (Perf.mode == Perf.AUTO) " · " + L.t(if (Perf.saving) "saving battery now" else "smooth now") else "") { choosePerf("mode") },
                switchRow("Map effects: rain, snow, glow, scanlines, drafting grid", Perf.effects) { Perf.effects = it },
                settingRow("Animations", L.t(PERF_ANIM[Perf.animations])) { choosePerf("anim") },
                settingRow("Map smoothness while driving", L.t(PERF_SMOOTH[Perf.smoothness])) { choosePerf("smooth") },
                switchRow("Live friend updates (off: checked every 15 min)", Perf.liveFriends) { Perf.liveFriends = it; Friends.Live.start() },
                switchRow("Save GPS when stopped", Perf.saveGpsStopped) { Perf.saveGpsStopped = it },
                settingRow(L.t("Last drive") + ": " + (Perf.lastDrive() ?: L.t("Measured on your next drive of 5 minutes or more")), null) {},
                if (Perf.struggling) settingRow(L.t("Effects paused") + ": " + L.t("The map was slow on this phone, so effects are off until the app restarts"), null) {} else null,
            )
            else -> listOf(
                settingRow("Units", if (MapState.imperial) "Miles" else "Kilometres") { MapState.setImperial(!MapState.imperial) },
                settingRow("Open map links in GameRoute", if (mapLinksOn()) "On" else "Off") { explainMapLinks() },
                settingRow("Star ratings & reviews", if (Reviews.enabled) "Google key set" else "Off: Reviews opens Google Maps") { chooseGoogleKey() },
                settingRow("Show the welcome screens again", null) { closeSheet(); showWelcome() },
                switchRow("Classic voice timing (exact distances)", Voice.classic) { Voice.classic = it },
                settingRow(L.t("What's new") + if (!prefs().getBoolean("seenWhatsNew113", false)) "  · " + L.t("NEW") else "", L.t("Version ") + BuildConfig.VERSION_NAME.removePrefix("build-")) { settingsPage = "whatsnew"; refreshChrome() },
            )
        }
    }

    /** One group in the settings list: coloured icon, name, what's inside, ›. */
    private fun settingsEntry(g: SettingsGroup) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val p = (12 * d).toInt(); setPadding(p, p, p, p)
        background = bg(Color.argb(26, Color.red(ink), Color.green(ink), Color.blue(ink)), radius = 14f)
        setOnClickListener { settingsPage = g.key; refreshChrome() }
        val tint = Ui.onPanel(theme, g.tint, 3.0)
        addView(android.widget.ImageView(context).apply {
            setImageResource(g.icon); imageTintList = android.content.res.ColorStateList.valueOf(tint)
            val q = (8 * d).toInt(); setPadding(q, q, q, q)
            background = bg(Color.argb(40, Color.red(tint), Color.green(tint), Color.blue(tint)), radius = 12f)
        }, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(g.title, 16f, bold = true).apply { setTextColor(ink) })
            addView(label(g.summary, 13f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label("›", 22f).apply { setTextColor(dim()); setPadding((8 * d).toInt(), 0, 0, 0) })
    }

    private fun chooseGoogleKey() {
        val p = (20 * d).toInt()
        val input = android.widget.EditText(this).apply {
            setText(Reviews.key); hint = "Google Maps Platform API key"; setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(p, p / 2, p, 0)
            addView(TextView(context).apply {
                textSize = 14f
                text = "Stars and reviews come from Google. Paste a Google Maps Platform key with " +
                    "\"Places API (New)\" turned on (console.cloud.google.com). Google gives a free monthly " +
                    "allowance; a personal app normally stays inside it.\n\nWithout a key, \"Reviews\" opens the " +
                    "place in Google Maps."
            })
            addView(input)
        }
        GameBuilder(this@MainActivity).setTitle("Star ratings & reviews").setView(box)
            .setPositiveButton("Save") { _, _ -> Reviews.key = input.text.toString(); showSettings() }
            .setNeutralButton("Remove") { _, _ -> Reviews.key = ""; showSettings() }
            .setNegativeButton("Cancel", null)
            .showT()
    }

    private fun chooseLanguage() {
        val opts = arrayOf("Auto (phone's language)", "English", "Ελληνικά")
        val cur = when (L.mode) { "en" -> 1; "el" -> 2; else -> 0 }
        GameBuilder(this@MainActivity).setTitle("Language")
            .setSingleChoiceItems(opts, cur) { dlg, k ->
                dlg.dismiss()
                L.setMode(when (k) { 1 -> "en"; 2 -> "el"; else -> "auto" })
                recreate()
            }.showT()
    }

    private fun chooseVoice() {
        val opts = arrayOf("English", "Ελληνικά", "Off")
        val cur = if (Voice.muted) 2 else if (Voice.lang == Phrases.GREEK) 1 else 0
        GameBuilder(this@MainActivity).setTitle("Spoken directions")
            .setSingleChoiceItems(opts, cur) { dlg, k ->
                when (k) {
                    0 -> { Voice.lang = Phrases.ENGLISH; Voice.muted = false }
                    1 -> { Voice.lang = Phrases.GREEK; Voice.muted = false }
                    else -> Voice.muted = true
                }
                dlg.dismiss()
                if (k < 2) Voice.say(if (k == 1) "Οι οδηγίες θα ακούγονται στα ελληνικά" else "Directions will be spoken in English")
            }.showT()
    }

    private val TALK = arrayOf("Normal", "Fewer", "Turns only")

    /** Test builds: the drive simulator's tricks. */
    private fun devDriveTools() {
        if (!Dev.simActive) { Toast.makeText(this, "Start a simulated drive first", Toast.LENGTH_SHORT).show(); return }
        val opts = arrayOf("Leave the route here", "Weak GPS (30 s)", "Tunnel: no GPS (30 s)", "No internet (30 s)", "Show a faster-route offer", "Arrive now", "Stop the simulation", "Fast forward…")
        GameBuilder(this@MainActivity).setTitle("While simulating").setItems(opts) { _, i ->
            when (i) {
                0 -> Dev.leaveRoute(); 1 -> Dev.weakGps(); 2 -> Dev.tunnel(); 3 -> Dev.noSignal()
                4 -> Dev.showOffer(); 5 -> Dev.arriveNow(); 6 -> Dev.stopSim()
                7 -> { chooseFastForward(); return@setItems }
            }
            closeSheet()
        }.showT()
    }

    /** Test builds: how much faster than the road the simulated drive goes. */
    private fun chooseFastForward() {
        val opts = listOf(1, 2, 4, 8)
        GameBuilder(this@MainActivity).setTitle("Fast forward").setMessage("The simulated drive (and the test convoy) go this many times faster than the road allows. The voice may skip some turns at 8×.")
            .setItems(opts.map { if (it == 1) "Off · the road's speed" else "$it× faster" }.toTypedArray()) { _, i ->
                Dev.fastForward = opts[i]
                if (sheetKind.isNotEmpty()) showSettings()
            }.showT()
    }

    /** An XP tool, then its celebration straight away (over the Developer page, which stays open). */
    private fun devXp(change: () -> Unit) {
        val before = Explorer.level
        change()
        val after = Explorer.level
        // going down: the levels above count as new again
        if (after < prefs().getInt("levelCelebrated", after)) prefs().edit().putInt("levelCelebrated", after).apply()
        showSettings()
        if (after > before) {
            prefs().edit().putInt("levelCelebrated", before).apply()
            celebrating = false
            showLevelUp(before)
        }
    }

    private fun devBackupNote() = if (Dev.hasBackup(this)) "Your real progress is already backed up." else "Your real progress is backed up first. Restore it any time from this page."

    /** Backup & restore: save everything to a file you keep, or bring it back from one. */
    private fun chooseBackup() {
        val opts = arrayOf(
            L.t("Save a backup") + "\n" + L.t("Level, prestige, Garage, trips, places, friends and settings, in one file"),
            L.t("Restore from a backup") + "\n" + L.t("Replaces what's on this phone, then the app restarts"))
        GameBuilder(this@MainActivity).setTitle(L.t("Backup & restore")).setItems(opts) { _, k ->
            try {
                if (k == 0) {
                    val name = "GameRoute backup " + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, name), REQ_BACKUP_SAVE)
                } else {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_BACKUP_OPEN)
                }
            } catch (e: Exception) { Toast.makeText(this, L.t("No app here can save or open files"), Toast.LENGTH_LONG).show() }
        }.showT()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_BACKUP_SAVE -> try {
                contentResolver.openOutputStream(uri, "wt")?.use { Backup.export(this, it) }
                Toast.makeText(this, L.t("Backup saved"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(this, L.t("Couldn't save the backup"), Toast.LENGTH_LONG).show() }
            REQ_BACKUP_OPEN -> {
                val root = try { contentResolver.openInputStream(uri)?.use { Backup.peek(it) } } catch (e: Exception) { null }
                if (root == null) { Toast.makeText(this, L.t("That isn't a GameRoute backup"), Toast.LENGTH_LONG).show(); return }
                GameBuilder(this@MainActivity).setTitle(L.t("Restore this backup?")).setMessage(Backup.describe(root) + "\n\n" + L.t("What's on this phone now is replaced. The app restarts."))
                    .setPositiveButton(L.t("Restore")) { _, _ ->
                        if (Backup.restore(this, root)) {
                            // start again, so everything reads the restored progress
                            val again = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            if (again != null) startActivity(again)
                            finishAffinity(); Runtime.getRuntime().exit(0)
                        }
                    }.setNegativeButton("Cancel", null).showT()
            }
        }
    }

    private val REQ_BACKUP_SAVE = 4101
    private val REQ_BACKUP_OPEN = 4102

    private fun prefs() = getSharedPreferences("gamemap", MODE_PRIVATE)

    /** Settings › Advanced › What's new: version 100. */
    private val WHATS_NEW = listOf(
        Triple("🏆", "Prestige ranks", "Reach level 50, start again as Bronze, Silver… with rewards and more XP"),
        Triple("🎉", "Celebrations every 5 levels", "In your map style, once you've parked"),
        Triple("📅", "Your recap", "Your month or year on the road, as a card to share"),
        Triple("🟢", "Your status", "Online, Do not disturb or Invisible"),
        Triple("🧭", "New turn box", "A sketch of every junction, in every style, and the road you're on"),
        Triple("🗣", "Clearer voice", "Round distances at the right moments; choose how much it talks"),
        Triple("🚗", "Friends in the car", "Invite, start convoys, quick messages, turns on the car's own display"),
        Triple("⚡", "Performance", "A smoother map and a longer battery, your choice in Settings"),
        Triple("📡", "Better GPS", "Stays on the road, ignores jumps, warns when the signal is weak"),
        Triple("💾", "Backup & restore", "Keep your XP, friends and places when you change phones"),
    )
    private val PERF_MODE = arrayOf("Auto", "Smooth", "Battery")
    private val PERF_ANIM = arrayOf("Full", "Reduced", "Off")
    private val PERF_SMOOTH = arrayOf("High", "Normal", "Low")

    private fun choosePerf(what: String) {
        val (title, opts, cur) = when (what) {
            "mode" -> Triple("Mode", arrayOf(
                L.t("Auto") + "\n" + L.t("Smooth, saves battery when Battery Saver is on or below 20%"),
                L.t("Smooth") + "\n" + L.t("Always smooth"),
                L.t("Battery") + "\n" + L.t("Always saves battery: no effects, fewer frames")), Perf.mode)
            "anim" -> Triple("Animations", arrayOf(
                L.t("Full") + "\n" + L.t("Celebrations in your style, smooth screen changes"),
                L.t("Reduced") + "\n" + L.t("Simple cards, quick fades"),
                L.t("Off") + "\n" + L.t("Nothing moves, everything appears at once")), Perf.animations)
            else -> Triple("Map smoothness while driving", arrayOf(
                L.t("High") + "\n" + L.t("Up to 60 frames a second, smoothest"),
                L.t("Normal") + "\n" + L.t("30 while driving, 20 when slow"),
                L.t("Low") + "\n" + L.t("15, best for older phones and long trips")), Perf.smoothness)
        }
        GameBuilder(this@MainActivity).setTitle(L.t(title))
            .setSingleChoiceItems(opts, cur) { dlg, k ->
                when (what) { "mode" -> Perf.mode = k; "anim" -> Perf.animations = k; else -> Perf.smoothness = k }
                dlg.dismiss(); showSettings()
            }.showT()
    }

    /** Normal: every heads-up and the turn. Fewer: one heads-up and the turn. Turns only: just the turn. */
    private fun chooseTalk() {
        val opts = arrayOf(L.t("Normal") + "\n" + L.t("In 200 m, then at the turn (motorway: 1 km, 500 m)"),
            L.t("Fewer") + "\n" + L.t("One heads-up, then at the turn"),
            L.t("Turns only") + "\n" + L.t("Only at the turn"))
        GameBuilder(this@MainActivity).setTitle(L.t("How much the voice talks"))
            .setSingleChoiceItems(opts, Voice.talk) { dlg, k -> Voice.talk = k; dlg.dismiss(); showSettings() }
            .showT()
    }

    private fun chooseAutoSwitch() {
        val opts = arrayOf("Switch automatically", "Ask me (keep my route)")
        GameBuilder(this@MainActivity).setTitle("When a faster route is found")
            .setSingleChoiceItems(opts, if (NavEngine.autoSwitch) 0 else 1) { dlg, k ->
                NavEngine.autoSwitch = k == 0; dlg.dismiss(); refreshChrome()
            }.showT()
    }

    private fun chooseLightMode() {
        val modes = MapState.LightMode.entries
        GameBuilder(this@MainActivity)
            .setTitle("Light / dark mode")
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), MapState.lightMode.ordinal) { dlg, m ->
                MapState.setLightMode(modes[m]); dlg.dismiss()
            }.showT()
    }

    // ------------------------------------------------------------------ convoy

    /** Asks for the name the group sees, once. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** gamemap://convoy/<code> (from the convoy web page) joins the group. */
    private fun handleLink(i: Intent?) {
        if (i == null) return
        // from a friends notification: join the convoy they invited me to, or open Friends
        i.getStringExtra("friend_join")?.let { cv ->
            i.removeExtra("friend_join"); Friends.cancelNotification(cv); main.post { joinConvoy(cv) }; return
        }
        if (i.getStringExtra("friends") != null) { i.removeExtra("friends"); main.post { openSheet("friends") }; return }
        // the home screen widget: Home or Work (routes to choose from), or just the app
        i.getStringExtra("widget_go")?.let { k ->
            i.removeExtra("widget_go")
            Places.saved(k)?.let { pl -> main.postDelayed({ NavEngine.plan(this, pl) }, 300) } ?: main.post { Toast.makeText(this, L.t(if (k == "home") "Set Home first" else "Set Work first"), Toast.LENGTH_SHORT).show() }
            return
        }
        val u = i.data
        if (u != null && u.scheme == "gamemap" && u.host == "friend") {
            val code = u.lastPathSegment ?: u.fragment ?: return
            i.data = null
            main.post { addFriendFromLink(code) }
            return
        }
        if (u != null && u.scheme == "gamemap" && u.host == "convoy") {
            val code = u.lastPathSegment ?: u.fragment ?: return
            main.post { joinConvoy(code) }
            return
        }
        // a location from another app: a geo: or maps link, or a shared message with one inside
        val text = when (i.action) {
            Intent.ACTION_VIEW -> u?.toString()
            Intent.ACTION_SEND -> listOfNotNull(i.getStringExtra(Intent.EXTRA_TEXT), i.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString("\n")
            else -> null
        } ?: return
        if (text.isBlank()) return
        i.action = null; i.data = null // handled once, not again on rotation
        openShared(text)
    }

    /** Shows the route to a location shared from another app (WhatsApp, Viber, Google Maps…). */
    private fun openShared(text: String) {
        Toast.makeText(this, L.t("Opening the shared location…"), Toast.LENGTH_SHORT).show()
        io.execute {
            val f = try { SharedLocation.find(text) } catch (e: Exception) { null }
            val place: Place? = when {
                f?.pos != null -> {
                    val a = try { Places.addressAt(f.pos) } catch (e: Exception) { null }
                    when {
                        f.label != null -> Place(f.label, a?.let { listOf(it.name, it.address).filter { x -> x.isNotBlank() }.joinToString(", ") } ?: "", f.pos, "📍")
                        a != null -> a.copy(pos = f.pos)
                        else -> Place(L.t("Shared location"), "", f.pos, "📍")
                    }
                }
                f?.query != null -> try { Places.find(f.query, LocationTracker.position, full = true)?.firstOrNull() } catch (e: Exception) { null }
                else -> null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (place == null) { Toast.makeText(this, L.t("Couldn't find a place in what was shared"), Toast.LENGTH_LONG).show(); return@runOnUiThread }
                closeSearch(); if (placeShown != null) closePlace()
                if (NavEngine.active && !NavEngine.arrived) showPlace(place, dropped = false) // driving: Add stop / Go instead
                else NavEngine.plan(this, place)
            }
        }
    }

    /** Tapped Google Maps links open GameRoute (Android 12+: the person has to allow it in Android's settings). */
    private fun mapLinksOn(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true // older Android asks which app every time
        return try {
            val st = getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
                ?.getDomainVerificationUserState(packageName) ?: return false
            st.isLinkHandlingAllowed && listOf("maps.google.com", "maps.app.goo.gl").any {
                st.hostToStateMap[it] == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED
            }
        } catch (e: Exception) { false }
    }

    /** How to make tapped map links open here, with the two Android pages one tap away. */
    private fun explainMapLinks() {
        if (Build.VERSION.SDK_INT < 31) {
            GameBuilder(this@MainActivity).setTitle("Open map links in GameRoute")
                .setMessage("When you tap a Google Maps, Apple Maps, Waze or geo: link, Android asks which app to use: pick GameRoute (and \"Always\" if you like).\n\nYou can also share any link or coordinates to GameRoute, or paste them in the search box.")
                .setPositiveButton("OK", null).showT()
            return
        }
        val mapsApp = "com.google.android.apps.maps"
        val hasMaps = try { packageManager.getPackageInfo(mapsApp, 0); true } catch (e: Exception) { false }
        val steps = (if (hasMaps) "1. Google Maps › Open by default: turn off \"Open supported links\".\n2. " else "") +
            "GameRoute › Open by default: turn on \"Open supported links\", tap \"Add link\" and tick the maps addresses.\n\n" +
            "Android lets only one app open these links${if (hasMaps) ", which is why Google Maps goes first" else ""}. Without this, you can still share a link to GameRoute or paste it in the search box."
        val b = GameBuilder(this@MainActivity).setTitle("Open map links in GameRoute").setMessage(L.t(steps))
            .setPositiveButton("GameRoute settings") { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
                catch (e: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {} }
            }
            .setNegativeButton("Close", null)
        if (hasMaps) b.setNeutralButton("Google Maps settings") { _, _ ->
            try { startActivity(Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, android.net.Uri.parse("package:$mapsApp"))) }
            catch (e: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$mapsApp"))) } catch (_: Exception) {} }
        }
        b.showT()
    }

    /** Change the name the convoy sees (Settings › Convoy, or the convoy screen). */
    private fun editConvoyName(then: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Your name"; setSingleLine(); setText(Convoy.myName); setSelection(text.length)
            filters = arrayOf(android.text.InputFilter.LengthFilter(16))
        }
        GameBuilder(this@MainActivity).setTitle("Your name in convoys").setMessage("Shown next to your arrow on the others' maps.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> Convoy.myName = input.text.toString().ifBlank { "Driver" }; convoyKey = ""; refreshChrome(); then?.invoke() }
            .setNegativeButton("Cancel", null).showT()
    }

    private fun withConvoyName(then: () -> Unit) {
        if (Convoy.myName.isNotBlank()) { then(); return }
        val input = EditText(this).apply { hint = "Your name"; setSingleLine() }
        GameBuilder(this@MainActivity).setTitle("Your name in convoys").setMessage("Shown next to your arrow on the others' maps.")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> Convoy.myName = input.text.toString().ifBlank { "Driver" }; then() }
            .setNegativeButton("Cancel", null).showT()
    }

    private fun startConvoyFromPreview() {
        val dest = NavEngine.planPlace ?: return
        if (Convoy.active) { suggestToConvoy(dest); return }
        withConvoyName {
            Convoy.create(dest)
            openSheet("convoy") // (Invite is there; no share sheet popping up by itself)
        }
    }

    /**
     * Already in a convoy: the place goes to the others as a suggestion (each gets a notification
     * to confirm or cancel); never a second convoy.
     */
    private fun suggestToConvoy(pl: Place) {
        Convoy.setDestination(pl)
        val msg = if (Convoy.members.isEmpty()) L.t("Convoy's destination: ${pl.name}")
            else L.t("Suggested to the convoy: ${pl.name}") + "\n" + L.t("The others are asked to confirm.")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        openSheet("convoy")
    }

    private fun inviteToConvoy() {
        val link = Convoy.link() ?: return
        val dest = Convoy.destination?.name ?: "our destination"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Join my convoy to $dest in GameRoute: code ${Convoy.pretty()}\n$link")
        }
        startActivity(Intent.createChooser(send, "Invite to the convoy"))
    }

    private fun joinConvoy(link: String) {
        withConvoyName {
            if (Convoy.join(link)) {
                // joined from an invite: who and how they drive (step 2 offers the same, picked)
                val inv = if (Friends.ready) Friends.core.invites.firstOrNull { it.convoy == Convoy.code } else null
                prefs().edit().putString("convoyInviteMode", if (inv != null) "${Convoy.code}|${inv.from}|${inv.mode}" else "").apply()
                openSheet("convoy")
            }
            else Toast.makeText(this, L.t("That isn't a convoy code or link"), Toast.LENGTH_SHORT).show()
        }
    }

    private var convoyKey = ""

    /**
     * Save a place, with an optional label and icon ("👤 Dad"): labelled places become shortcuts
     * on the home screen and in the car, and come first in search. Saved already: edit or remove.
     */
    private fun showSaveDialog(pl: Place, then: () -> Unit) {
        val old = Places.savedAt(pl.pos)
        val p = (16 * d).toInt()
        val icons = listOf("★", "👤", "❤️", "👪", "🏋", "🍽", "☕", "🎓", "⚽", "🛒")
        var icon = old?.icon ?: "★"
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p + p / 2, p / 2, p + p / 2, 0) }
        box.addView(label(old?.name ?: pl.name, 15f, bold = true).apply { setTextColor(ink) })
        val addr = old?.address ?: pl.address
        if (addr.isNotBlank()) box.addView(label(addr, 13f).apply { setTextColor(dim()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        box.addView(label("LABEL (OPTIONAL)", 11f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding(0, p, 0, p / 4) })
        val input = EditText(this).apply {
            hint = "e.g. Dad, Gym, Grandma"; setSingleLine(); setText(old?.label.orEmpty()); setSelection(text.length)
            setTextColor(ink); setHintTextColor(dim(110))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        box.addView(input)
        box.addView(label("With a label it gets its own button on the home screen and in the car, and typing it in search finds it first.", 12f).apply {
            setTextColor(dim()); setPadding(0, p / 4, 0, p / 2)
        })
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun paintIcons() { for (i in 0 until iconRow.childCount) (iconRow.getChildAt(i) as TextView).let { v ->
            val on = v.contentDescription == icon
            v.background = if (on) bg(Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, 2f, 20f) else null
        } }
        for (e in icons) iconRow.addView(TextView(this).apply {
            text = e; textSize = 20f; gravity = Gravity.CENTER; contentDescription = e; setTextColor(ink)
            setOnClickListener { icon = e; paintIcons() }
        }, LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f))
        paintIcons()
        box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(iconRow, LinearLayout.LayoutParams((icons.size * 42 * d).toInt(), WRAP_CONTENT)) })
        val dlg = GameBuilder(this@MainActivity)
            .setTitle(if (old != null) "Saved place" else "Save place")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val lab = input.text.toString().trim()
                Places.saveFavourite(pl, lab.ifBlank { null }, icon.takeIf { it != "★" || lab.isNotBlank() })
                Toast.makeText(this, L.t(if (lab.isNotBlank()) "Saved as $lab" else "Saved: ${pl.name}"), Toast.LENGTH_SHORT).show()
                lastHomeKey = ""; refreshChrome(); then()
            }
            .setNegativeButton("Cancel", null)
        if (old != null) dlg.setNeutralButton("Remove") { _, _ ->
            Places.unsave(pl)
            Toast.makeText(this, L.t("Removed from saved"), Toast.LENGTH_SHORT).show()
            lastHomeKey = ""; refreshChrome(); then()
        }
        dlg.showT()
    }
    private var proposalAsked = 0L

    /** Change ▾ on the convoy's destination: another place, or None (just share where we all are). */
    private fun showDestinationMenu(anchor: View) {
        val p = (14 * d).toInt()
        lateinit var pop: android.widget.PopupWindow
        fun item(icon: String, title: String, sub: String?, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p * 3 / 4, p * 3 / 2, p * 3 / 4); minimumHeight = (52 * d).toInt(); isClickable = true
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(dim(60)), null, null)
            addView(label(icon, 18f).apply { setPadding(0, 0, p, 0) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 15f, bold = true).apply { setTextColor(ink) })
                if (sub != null) addView(label(sub, 12.5f).apply { setTextColor(dim()) })
            })
            setOnClickListener { pop.dismiss(); onClick() }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(panel, accent, 1.5f, 14f); elevation = 16 * d
            setPadding(0, p / 3, 0, p / 3)
            addView(item("📍", "Choose another place", null) { closeSheet(); startPicking("convoy") })
            addView(View(context).apply { setBackgroundColor(dim(50)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, d.toInt())).apply { marginStart = p; marginEnd = p })
            addView(item("🚫", "None", "Just share where we all are") { Convoy.clearDestination(); convoyKey = ""; refreshChrome() })
        }
        L.translateTree(box); Icons.applyTree(box)
        pop = android.widget.PopupWindow(box, (280 * d).toInt(), WRAP_CONTENT, true).apply { elevation = 16 * d; isOutsideTouchable = true }
        pop.showAsDropDown(anchor, -(200 * d).toInt(), 0, Gravity.END)
    }

    /** Someone in the convoy chose a (new) destination: go there too? */
    private fun askProposal() {
        val p = Convoy.proposal ?: return
        if (p.at == proposalAsked) return
        proposalAsked = p.at
        Convoy.cancelNotification()
        val driving = NavEngine.active && !NavEngine.arrived
        val place = p.place
        if (place == null) {
            // they removed the destination
            val mine = Convoy.destination?.takeIf { Convoy.onConvoyRoute }
            GameBuilder(this@MainActivity)
                .setTitle("${p.from} removed the destination")
                .setMessage(L.t("The convoy now just shares where everyone is.") +
                    (if (mine != null) "\n\n" + L.t("You're driving to ${mine.name}: your route stays unless you end it.") else ""))
                .setPositiveButton("OK, remove") { _, _ -> Convoy.accept(); refreshChrome() }
                .setNegativeButton("Keep mine") { _, _ -> Convoy.decline() }
                .setCancelable(false)
                .showT()
            return
        }
        GameBuilder(this@MainActivity)
            .setTitle("${p.from} suggests a place")
            .setMessage(place.name + (if (place.address.isNotBlank()) "\n" + place.address else "") +
                (if (driving) "\n\n" + L.t("Your route will change to it.") else ""))
            .setPositiveButton(if (driving) "Confirm · go there" else "Confirm") { _, _ -> Convoy.accept(); refreshChrome() }
            .setNegativeButton("Cancel") { _, _ -> Convoy.decline() }
            .setCancelable(false)
            .showT()
    }

    /** Takes the map to a friend in the convoy (me: back to my car) and shows their card. */
    private fun showFriend(id: String) {
        closeSheet()
        if (id == Convoy.myId) { MapState.phone.recenter(); return }
        val m = Convoy.members[id] ?: return
        val at = m.shown ?: m.pos ?: run { Toast.makeText(this, L.t("No position from them yet"), Toast.LENGTH_SHORT).show(); return }
        MapState.phone.pan(at, 0f)
        if (MapState.phone.targetZoom < 15.0) MapState.phone.zoomBy(15.5 - MapState.phone.targetZoom)
        showPlace(Convoy.placeOf(m), dropped = false)
    }

    private fun confirmLeaveConvoy() {
        GameBuilder(this@MainActivity).setTitle("Leave the convoy?").setMessage("The others will stop seeing you.")
            .setPositiveButton("Leave") { _, _ -> Convoy.leave(); if (sheetKind == "convoy" || sheetKind == "quick") closeSheet() }
            .setNegativeButton("Stay", null).showT()
    }

    /** Convoy sheet: the code box is open (Join with a code). */
    private var convoyJoinOpen = false

    /** A coloured round letter for someone in the convoy. */
    private fun dot(name: String, color: Int, size: Int = 36, stale: Boolean = false) = label(name.trim().take(1).uppercase().ifBlank { "?" }, size * 0.42f, bold = true).apply {
        gravity = Gravity.CENTER; setTextColor(Color.rgb(17, 17, 17))
        background = bg(if (stale) Color.GRAY else color, radius = size / 2f)
        layoutParams = LinearLayout.LayoutParams((size * d).toInt(), (size * d).toInt()).apply { marginEnd = (12 * d).toInt() }
    }

    private fun badge(text: String, color: Int, filled: Boolean = false) = label(text, 12.5f, bold = true).apply {
        val q = (4 * d).toInt(); setPadding(q * 2, q, q * 2, q)
        if (filled) { setTextColor(onAccent(color)); background = bg(color, radius = 8f) }
        else { setTextColor(readable(color)); background = bg(Color.TRANSPARENT, color, 1.5f, 8f) }
    }

    private fun sectionTitle(t: String) = label(t, 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding((3 * d).toInt(), (14 * d).toInt(), 0, (6 * d).toInt()) }

    private fun renderConvoy() {
        val rows = Convoy.rows(accent)
        val fk = if (!Friends.ready) "" else Convoy.members.values.joinToString { m -> m.fc + Friends.core.isFriend(m.fid) + Friends.core.requested(m.fc) }
        val key = "${Convoy.code}|${Convoy.myName}|${Convoy.destination?.name}|${rows.joinToString { "${it.name}${it.kmh}${it.state}${it.eta / 60000}${(it.progress * 50).toInt()}${it.ready}${it.stale}${it.along}" }}|" +
            "${Convoy.sharers().size}|${Convoy.meSharing}|${Convoy.meAlong}|${Convoy.meReady}|${Convoy.counting}|${NavEngine.active}|${theme.name}|${MapState.dark}|$fk|${friendsWaiting()}|" +
            "${onlineFriends().joinToString { it.fid }}|$convoyJoinOpen|${Convoy.host}|${convoyModeChosen()}|$pendingMode|${NavEngine.arrived}|${Convoy.myPlace()}"
        if (key == convoyKey && sheetHost.childCount > 0) return
        convoyKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        val p = (12 * d).toInt()
        togetherHeader("convoy")
        if (!Convoy.active) { renderConvoyStart(p); return }

        val dest = Convoy.destination
        val underway = Convoy.underway
        val choosing = !NavEngine.active && dest != null && Convoy.atStart().isNotEmpty() && !underway
        val beforeStart = !NavEngine.active && dest != null && !underway
        // one question at a time: 1 where → 2 how → 3 the lobby; then driving; then the standings
        if (dest == null && !Convoy.meSharing && !NavEngine.active && !underway) { renderConvoyWhere(p); return }
        if (beforeStart && !convoyModeChosen()) { renderConvoyHow(p); return }
        if (raceStandingsShown()) { renderRaceStandings(p, rows); return }
        if (NavEngine.active && !NavEngine.arrived && Convoy.onConvoyRoute) { renderConvoyDriving(p, rows); return }

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.56).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (beforeStart) body.addView(stepBar(3))

        // quick messages: one tap, everyone gets it (and hears it while driving)
        if (Convoy.members.isNotEmpty()) body.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, p * 2 / 3)
                val icons = mapOf("fuel" to "⛽", "stop" to "⏸", "ahead" to "▶", "wait" to "✋")
                for ((k, _, _) in Convoy.quick) addView(pill((icons[k] ?: "") + " " + Convoy.messageText(k)) {
                    Convoy.send(k)
                    Toast.makeText(this@MainActivity, L.t("Sent to the convoy"), Toast.LENGTH_SHORT).show()
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p / 2 })
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // where we're going, with Change (or Choose)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p * 3 / 4, p / 2, p * 3 / 4); background = bg(dim(22), dim(70), 1f, 14f)
            addView(label("🏁", 20f).apply { gravity = Gravity.CENTER; setTextColor(readable(accent)) }, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(dest?.name ?: L.t("No destination yet"), 17f, bold = true).apply {
                    setTextColor(if (dest == null) dim() else ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                val here = LocationTracker.position
                val sub = when {
                    dest == null -> L.t("Choose it now; everyone gets it")
                    here != null -> L.t("${NavEngine.formatDistance(Geo.distance(here, dest.pos))} from you") + " · " + L.t("code ${Convoy.pretty()}")
                    else -> L.t("code ${Convoy.pretty()}")
                }
                addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (dest == null) addView(pill("📍 Choose") { closeSheet(); startPicking("convoy") }.apply {
                textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 14f)
            })
            else addView(pill("Change") { showDestinationMenu(this) }.apply { textSize = 14f; setTextColor(readable(accent)) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })

        // how far along everyone is (once someone is on the way)
        if (!beforeStart && dest != null) body.addView(object : View(this) {
            override fun onDraw(c: Canvas) {
                val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                val y = height / 2f; val x0 = 10 * d; val x1 = width - 34 * d
                pnt.color = dim(70); c.drawRoundRect(x0, y - 1.5f * d, x1, y + 1.5f * d, 2 * d, 2 * d, pnt)
                pnt.textSize = 14 * d; c.drawText("🏁", x1 + 6 * d, y + 5 * d, pnt)
                for (r in rows.sortedBy { if (it.me) 1 else 0 }) {
                    pnt.color = if (r.stale || r.along) Color.GRAY else r.color
                    c.drawCircle(x0 + ((x1 - x0) * r.progress).toFloat(), y, 8 * d, pnt)
                }
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, (34 * d).toInt()))

        // who's in it
        val sharers = Convoy.sharers()
        val count = rows.size + sharers.size + (if (Convoy.meSharing) 1 else 0)
        body.addView(sectionTitle(L.t("$count IN THE CONVOY")))
        val green = Color.rgb(47, 180, 100)
        for (r in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 3, 0, p / 3) }
            row.addView(dot(if (r.me) Convoy.myName.ifBlank { "You" } else r.name, r.color, 36, r.stale))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(if (r.me) L.t("You") + (if (Convoy.host) " · " + L.t("host") else "") else r.name, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val lv = if (r.me) (if (Xp.showToFriends) Xp.badge() else "") else Convoy.members[r.id]?.lv.orEmpty()
                if (lv.isNotBlank()) addView(label(lv, 12.5f, bold = true).apply { setTextColor(readable(accent)); maxLines = 1 })
                // at the start the badge says it all; on the way: how far ahead or behind
                val atStartRow = r.state == "ready" && dest != null && !NavEngine.active
                val sub = if (atStartRow) (if (r.stale) L.t("no signal") else "") else Convoy.relative(r)
                if (sub.isNotBlank()) addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (r.state == "ready" && dest != null && !NavEngine.active) {
                // at the start: who races, who's ready, who drives along
                val ready = if (r.me) Convoy.meReady else r.ready
                row.addView(when {
                    r.along -> badge(L.t("Along"), dim(160))
                    ready -> badge(L.t("Ready ✓"), green, filled = true)
                    else -> badge(L.t("Racing"), accent)
                })
            } else {
                row.addView(label(if (r.state == "arrived") "✓" else "${r.kmh} km/h", 14f, bold = true).apply {
                    setTextColor(if (r.kmh == 0 && r.state == "driving") readable(Color.rgb(230, 60, 60)) else ink); setPadding(p, 0, p, 0)
                })
                row.addView(label(if (r.eta > 0) NavEngine.clock(r.eta) else "–", 15f, bold = true).apply { setTextColor(readable(green)) })
            }
            if (!r.me) friendMark(r.id)?.let { row.addView(it) }
            row.isClickable = true
            row.setOnClickListener { showFriend(r.id) }
            body.addView(row)
        }
        // only sharing their location: at the bottom of the list
        val here = LocationTracker.position
        for (m in (if (Convoy.meSharing) listOf<Convoy.Member?>(null) else emptyList()) + sharers) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 3, 0, p / 3) }
            val col = if (m == null) rows.firstOrNull { it.me }?.color ?: accent else m.color
            row.addView(dot(m?.name ?: Convoy.myName.ifBlank { "You" }, col, 36, m?.stale == true))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(m?.name ?: L.t("You"), 16f, bold = true).apply { setTextColor(ink) })
                val sub = when {
                    m == null -> L.t("sharing your location")
                    m.stale -> L.t("no signal")
                    here != null && m.pos != null -> L.t("${NavEngine.formatDistance(Geo.distance(here, m.pos!!))} from you")
                    else -> L.t("not driving")
                }
                addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(badge("📍 " + L.t("Sharing"), Color.rgb(77, 171, 247)))
            if (m != null) { friendMark(m.id)?.let { row.addView(it) }; row.isClickable = true; row.setOnClickListener { showFriend(m.id) } }
            body.addView(row)
        }
        if (Convoy.members.isEmpty()) body.addView(label("Nobody else yet: invite friends or show the QR code.", 14f).apply { setTextColor(dim()); setPadding(0, p / 3, 0, 0) })
        // one way to invite: friends, QR, link
        body.addView(pill("👥  Invite · friends, QR or link") { openSheet("convoyinvite") }.apply {
            textSize = 15f; setTextColor(readable(accent))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * d; setStroke((1.5f * d).toInt(), accent, 7 * d, 5 * d)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, (50 * d).toInt()).apply { topMargin = p * 2 / 3 })

        // me: how I'm driving, with Change (back to the "how" step; free until the 3-2-1)
        if (beforeStart) {
            val lobby = Convoy.lobby()
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(p, p * 2 / 3, p / 2, p * 2 / 3); background = bg(dim(22), dim(70), 1f, 14f)
                addView(label(L.t("You: ") + L.t(when { Convoy.meSharing -> "📍 Just sharing"; Convoy.meAlong -> "🚗 Driving together"; else -> "🏁 Racing" }), 15.5f, bold = true).apply { setTextColor(ink) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (!Convoy.counting) addView(pill("Change") { setConvoyModeChosen(false); pendingMode = null; convoyKey = ""; renderConvoy() }.apply { textSize = 14f; setTextColor(readable(accent)) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p })
            // what's happening, and what's next
            val waiting = lobby.filter { !it.ready }.map { it.name }
            body.addView(label(when {
                Convoy.meSharing -> "The others see you on their map. You're not in the race and no route starts."
                Convoy.meAlong -> "Same route, no race: go whenever you like."
                lobby.isEmpty() -> "Nobody else is racing yet: invite people, or start on your own."
                Convoy.counting -> "3-2-1…"
                waiting.isNotEmpty() -> L.t("Waiting for ") + waiting.joinToString(", ") + L.t(" to be ready. Then 3-2-1 and go.")
                else -> "Everyone's ready: 3-2-1 and go."
            }, 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(p / 4, p, p / 4, 0) })
        }

        // the one big button
        val lobby = Convoy.lobby()
        val action: Pair<String, (() -> Unit)?>? = when {
            dest == null -> "📍  Choose where to go" to { closeSheet(); startPicking("convoy") }
            !Convoy.onConvoyRoute && (NavEngine.active || underway) && !Convoy.meSharing -> "➤  Join the convoy's route" to { closeSheet(); NavEngine.start(this, dest); MapState.recenter() }
            NavEngine.active || Convoy.meSharing -> null
            Convoy.meAlong -> "START · no race" to { closeSheet(); NavEngine.start(this, dest); MapState.recenter() }
            !choosing || lobby.isEmpty() -> "START" to { closeSheet(); NavEngine.start(this, dest); MapState.recenter() }
            Convoy.counting -> "3-2-1…" to null
            Convoy.meReady -> {
                val waiting = lobby.filter { !it.ready }.map { it.name }
                (if (waiting.isEmpty()) "✓ Everyone's ready!" else "✓ " + L.t("Ready · waiting for ") + waiting.joinToString(", ")) to { Convoy.setReady(false) }
            }
            Convoy.host -> "START" to { Convoy.setReady(true) }
            else -> "READY UP" to { Convoy.setReady(true) }
        }
        if (action != null) {
            val (text, act) = action
            val done = text.startsWith("✓")
            sheetHost.addView(pill(text) { act?.invoke() }.apply {
                textSize = 17f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                if (done) { setTextColor(readable(green)); background = bg(Color.TRANSPARENT, green, 2f, 16f) }
                else { setTextColor(onAccent(accent)); background = bg(accent, radius = 16f); typeface = theme.typeface }
                isEnabled = act != null
            }, LinearLayout.LayoutParams(MATCH_PARENT, (56 * d).toInt()).apply { topMargin = p })
            if (done || (choosing && !Convoy.meAlong && lobby.isNotEmpty() && !Convoy.counting)) sheetHost.addView(label(when {
                done -> "Tap it again if you're not ready after all."
                Convoy.host -> "You're the host. The others tap READY UP; when everyone's ready a 3-2-1 starts on every phone."
                else -> "When everyone's ready, a 3-2-1 starts on every phone and the route starts by itself."
            }, 12.5f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p / 3, 0, 0) })
        }
        // your name, and the way out
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 4, p * 2 / 3, 0, 0)
            addView(label(L.t("You appear as \"${Convoy.myName.ifBlank { "Driver" }}\"") + " · " + L.t("Change"), 13f, bold = true).apply {
                setTextColor(readable(accent)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setOnClickListener { editConvoyName() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("Leave") { confirmLeaveConvoy() }.apply { textSize = 14f; setTextColor(readable(Color.rgb(230, 70, 70))) })
        })
    }

    // ---------------------------------------------------------------- the convoy, step by step

    /** The "how" I picked on step 2 but haven't confirmed yet (null: as it is now). */
    private var pendingMode: String? = null

    /** Step 2 is done for this convoy (each convoy asks once; Change asks again). */
    private fun convoyModeChosen() = Convoy.code != null && prefs().getString("convoyModeFor", "") == Convoy.code
    private fun setConvoyModeChosen(v: Boolean) { prefs().edit().putString("convoyModeFor", if (v) Convoy.code.orEmpty() else "").apply() }

    private fun currentMode() = when { Convoy.meSharing -> "share"; Convoy.meAlong -> "along"; else -> "race" }

    /** 1 · Where — 2 · How — 3 · Who, with the step we're on lit. */
    private fun stepBar(step: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, (2 * d).toInt(), 0, (12 * d).toInt())
        listOf("Where", "How", "Who").forEachIndexed { i, t ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(View(context).apply { setBackgroundColor(if (i < step) accent else dim(60)) }, LinearLayout.LayoutParams(MATCH_PARENT, (4 * d).toInt()))
                addView(label("${i + 1} · " + L.t(t).uppercase(), 11f, bold = true).apply {
                    setTextColor(if (i == step - 1) readable(accent) else dim()); letterSpacing = 0.08f; setPadding(0, (5 * d).toInt(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = (6 * d).toInt() })
        }
    }

    /** A big choice row: icon, title, a line or two, and a round mark when it's the chosen one. */
    private fun choiceCard(icon: String, title: String, sub: String, selected: Boolean?, extra: String? = null, onClick: () -> Unit) = LinearLayout(this).apply {
        val p = (12 * d).toInt()
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p, p, p, p)
        background = if (selected == true) bg(dim(22), accent, 2f, 14f) else bg(dim(22), radius = 14f)
        isClickable = true; setOnClickListener { onClick() }
        contentDescription = L.t(title) + if (selected == true) ", " + L.t("selected") else ""
        addView(label(icon, 26f).apply { gravity = Gravity.CENTER; setTextColor(ink) }, LinearLayout.LayoutParams((40 * d).toInt(), WRAP_CONTENT).apply { marginEnd = p })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(L.t(title), 17f, bold = true).apply { setTextColor(ink) })
            addView(label(L.t(sub), 13.5f).apply { setTextColor(dim()) })
            if (extra != null) addView(label(L.t(extra), 12.5f, bold = true).apply { setTextColor(readable(accent)); setPadding(0, (4 * d).toInt(), 0, 0) })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // round marks, whatever the style's corners: a ring, and a dot in it for the chosen one
        // ("pick one"; the theme's cut squares read as checkboxes)
        if (selected != null) addView(View(context).apply {
            fun oval(fillC: Int, strokeC: Int, w: Float) = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fillC); if (w > 0) setStroke((w * d).toInt(), strokeC)
            }
            background = if (selected) android.graphics.drawable.LayerDrawable(arrayOf(oval(Color.TRANSPARENT, accent, 2.2f), oval(accent, 0, 0f))).apply {
                setLayerInset(1, (5.5f * d).toInt(), (5.5f * d).toInt(), (5.5f * d).toInt(), (5.5f * d).toInt())
            } else oval(Color.TRANSPARENT, dim(), 2f)
        }, LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()))
        else addView(label("›", 22f).apply { setTextColor(dim()) })
    }

    private fun bigButton(text: String, onClick: () -> Unit) = pill(text) { onClick() }.apply {
        textSize = 17f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setTextColor(onAccent(accent)); background = bg(accent, radius = 16f); typeface = theme.typeface
    }

    /** Step 1: where are you going together? */
    private fun renderConvoyWhere(p: Int) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.62).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(stepBar(1))
        body.addView(label(if (Convoy.host) "Where are you going together?" else "Where is the convoy going?", 19f, bold = true).apply { setTextColor(ink) })
        body.addView(label(if (Convoy.host) "Everyone in the convoy gets this place." else "Suggest a place: the others are asked to confirm.", 14f).apply { setTextColor(dim()); setPadding(0, p / 4, 0, p) })
        fun gap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p * 2 / 3 }
        // where I'm going already (on a route, or a place I'm looking at): one tap
        val now = if (NavEngine.active && !NavEngine.arrived) NavEngine.destination?.let { Place(NavEngine.destinationName, "", it) } else NavEngine.planPlace
        if (now != null) body.addView(choiceCard("➤", now.name, L.t("Where you're going now"), null) {
            Convoy.setDestination(now); convoyKey = ""; renderConvoy()
        }, gap())
        body.addView(choiceCard("🔍", "Search a place", "A place, an address or a saved one", null) { closeSheet(); startPicking("convoy") }, gap())
        for ((k, icon) in listOf("home" to "🏠", "work" to "💼")) Places.saved(k)?.let { pl ->
            val here = LocationTracker.position
            body.addView(choiceCard(icon, pl.name, listOfNotNull(here?.let { NavEngine.formatDistance(Geo.distance(it, pl.pos)) }, pl.address.takeIf { it.isNotBlank() }).joinToString(" · "), null) {
                Convoy.setDestination(pl); convoyKey = ""; renderConvoy()
            }, gap())
        }
        body.addView(label("No shared place: just see each other on the map ›", 14f, bold = true).apply {
            setTextColor(readable(Color.rgb(77, 171, 247))); setPadding(p / 4, p / 2, 0, p / 2)
            setOnClickListener { Convoy.setSharing(true); setConvoyModeChosen(true); convoyKey = ""; renderConvoy() }
        })
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, p / 2, 0, 0)
            addView(pill("Leave") { confirmLeaveConvoy() }.apply { textSize = 14f; setTextColor(readable(Color.rgb(230, 70, 70))) })
        })
    }

    /** Step 2 (and joining): how do you want to drive? Race · Drive together · Just share. */
    private fun renderConvoyHow(p: Int) {
        val dest = Convoy.destination
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.62).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // someone joining: whose convoy, and how the one who invited me drives (picked for me)
        val inv = if (Convoy.host) null else prefs().getString("convoyInviteMode", "").orEmpty().split("|").takeIf { it.size == 3 && it[0] == Convoy.code }
        val drivers = Convoy.members.size + 1
        if (Convoy.host) body.addView(stepBar(2))
        else {
            body.addView(label(L.t(if (inv != null) "${inv[1]}'s convoy" else "The convoy"), 20f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER })
            body.addView(label("🏁 " + L.t("To ${dest?.name ?: "—"}") + " · " + L.t("$drivers drivers"), 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p / 4, 0, p) })
        }
        body.addView(label(if (Convoy.host) "How do you want to drive?" else "How do you want to come?", 19f, bold = true).apply { setTextColor(ink) })
        if (Convoy.host) body.addView(label(L.t("To ${dest?.name ?: "—"}") + " · " + L.t("each person picks their own when they join."), 14f).apply { setTextColor(dim()); setPadding(0, p / 4, 0, p) })
        val chosen = pendingMode ?: inv?.get(2)?.takeIf { it.isNotBlank() } ?: currentMode()
        fun gap() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p * 2 / 3 }
        val pick: (String) -> Unit = { m -> pendingMode = m; convoyKey = ""; renderConvoy() }
        body.addView(choiceCard("🏁", if (Convoy.host) "Race" else "Join the race", "3-2-1 start together. Same place, fastest wins. Places and times at the end.", chosen == "race",
            if (Convoy.host) "The default for people you invite" else null) { pick("race") }, gap())
        body.addView(choiceCard("🚗", if (Convoy.host) "Drive together" else "Drive together, no race", "Same place, same route, no race. You see each other the whole way.", chosen == "along") { pick("along") }, gap())
        body.addView(choiceCard("📍", "Just share where I am", "I'm going somewhere else: the others see me on the map, I see them.", chosen == "share") { pick("share") }, gap())
        val label = when {
            Convoy.host -> "Next: invite people ›"
            chosen == "race" -> "Join the race ›"
            chosen == "along" -> "Drive together ›"
            else -> "Share where I am ›"
        }
        sheetHost.addView(bigButton(L.t(label)) {
            when (chosen) { "race" -> { Convoy.setSharing(false); Convoy.setAlong(false) }; "along" -> Convoy.setAlong(true); else -> Convoy.setSharing(true) }
            setConvoyModeChosen(true); pendingMode = null; convoyKey = ""
            if (Convoy.host && Convoy.members.isEmpty()) openSheet("convoyinvite") else renderConvoy()
        }, LinearLayout.LayoutParams(MATCH_PARENT, (56 * d).toInt()).apply { topMargin = p })
    }

    /** While driving: quick messages first, everyone in order with the gap, See everyone / Invite / Leave. */
    private fun renderConvoyDriving(p: Int, rows: List<Convoy.Row>) {
        val dest = Convoy.destination
        val racing = !Convoy.meAlong && !Convoy.meSharing && Convoy.members.values.any { !it.along && !it.sharing }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.6).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val racers = rows.filter { !it.along }
        val myPlace = racers.indexOfFirst { it.me } + 1
        body.addView(label((if (racing) "🏁 " + L.t("Race to ${dest?.name ?: ""}") else "🚗 " + L.t("Together to ${dest?.name ?: ""}")), 19f, bold = true).apply { setTextColor(ink); typeface = theme.typeface })
        body.addView(label(listOfNotNull(if (racing && myPlace > 0) L.t(ordinal(myPlace)) else null, NavEngine.formatDistance(NavEngine.remainingDistance) + " " + L.t("to go")).joinToString(" · "), 13.5f).apply {
            setTextColor(dim()); setPadding(0, p / 4, 0, p * 2 / 3)
        })
        // quick messages: four big tiles
        val icons = mapOf("fuel" to "⛽", "stop" to "☕", "ahead" to "👉", "wait" to "✋")
        body.addView(android.widget.GridLayout(this).apply {
            columnCount = 4
            for ((k, _, _) in Convoy.quick) addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, p / 2, 0, p / 2)
                background = bg(dim(22), radius = 12f); isClickable = true
                contentDescription = Convoy.messageText(k)
                setOnClickListener { Convoy.send(k); Toast.makeText(this@MainActivity, L.t("Sent to the convoy"), Toast.LENGTH_SHORT).show() }
                addView(label(icons[k] ?: "💬", 22f).apply { gravity = Gravity.CENTER })
                addView(label(Convoy.messageText(k), 12f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED), android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins(p / 4, 0, p / 4, 0)
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p * 2 / 3 })
        // everyone, in order (the racers by place, then those driving together)
        var place = 0
        for (r in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 2, p / 3, p / 2, p / 3) }
            if (r.me) row.background = bg(dim(26), radius = 10f)
            if (!r.along) place++
            row.addView(label(if (!r.along && racing) "$place" else "", 15f, bold = true).apply { setTextColor(readable(accent)) }, LinearLayout.LayoutParams((20 * d).toInt(), WRAP_CONTENT))
            row.addView(dot(if (r.me) Convoy.myName.ifBlank { "You" } else r.name, r.color, 32, r.stale))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(if (r.me) L.t("You") else r.name, 15.5f, bold = true).apply { setTextColor(ink) })
                val sub = (if (r.me) "" else L.t(Convoy.relative(r))) + (if (r.along && !r.me) "" else "")
                if (sub.isNotBlank()) addView(label(sub, 12.5f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(label(if (r.state == "arrived") "🏁" else "${r.kmh} km/h", 14f, bold = true).apply { setTextColor(readable(Color.rgb(61, 220, 132))) })
            if (!r.me) { row.isClickable = true; row.setOnClickListener { showFriend(r.id) } }
            body.addView(row)
        }
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
            addView(pill("👀 See all") {
                closeSheet()
                val pts = Convoy.members.values.mapNotNull { it.shown ?: it.pos } + listOfNotNull(LocationTracker.position)
                MapState.phone.requestFit(pts)
            }.apply { textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f) }, LinearLayout.LayoutParams(0, (46 * d).toInt(), 1f))
            addView(pill("👥 Invite") { openSheet("convoyinvite") }.apply { textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f) },
                LinearLayout.LayoutParams(0, (46 * d).toInt(), 1f).apply { marginStart = p / 2 })
            addView(pill("Leave") { confirmLeaveConvoy() }.apply { textSize = 14f; setTextColor(readable(Color.rgb(230, 70, 70))) },
                LinearLayout.LayoutParams(WRAP_CONTENT, (46 * d).toInt()).apply { marginStart = p / 2 })
        })
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "You're 1st"; 2 -> "You're 2nd"; 3 -> "You're 3rd"; else -> "You're ${n}th" }

    /** I finished a race and the convoy is still on: the standings (live until everyone's in). */
    private fun raceStandingsShown() = Convoy.active && NavEngine.arrived && Convoy.myPlace() != null

    private fun renderRaceStandings(p: Int, rows: List<Convoy.Row>) {
        val (mine, total) = Convoy.myPlace() ?: return
        val racers = rows.filter { !it.along }
        val arrived = racers.filter { it.state == "arrived" }.sortedBy { it.arrivedAt }
        val stillDriving = racers.filter { it.state != "arrived" }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.62).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val medal = when (mine) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "🏁" }
        body.addView(label(medal, 44f).apply { gravity = Gravity.CENTER })
        body.addView(label(L.t(when (mine) { 1 -> "You won!"; 2 -> "You finished 2nd"; 3 -> "You finished 3rd"; else -> "You finished ${mine}th" }), 22f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER; typeface = theme.typeface })
        val first = arrived.firstOrNull()
        val me = arrived.firstOrNull { it.me }
        if (me != null && first != null && !first.me) body.addView(label("+" + fmtGap((me.arrivedAt - first.arrivedAt) / 1000) + " · " + L.t("Winner: ") + first.name, 14f).apply { setTextColor(dim()); gravity = Gravity.CENTER })
        body.addView(sectionTitle(if (stillDriving.isEmpty()) L.t("FINAL ORDER · $total RACERS") else L.t("LIVE · ${stillDriving.size} STILL DRIVING")))
        val start = arrived.minOfOrNull { it.arrivedAt } ?: 0L
        arrived.forEachIndexed { i, r ->
            body.addView(standingRow("${i + 1}", r, L.t("Arrived ") + NavEngine.clock(r.arrivedAt) + (if (i > 0 && start > 0) " · +" + fmtGap((r.arrivedAt - start) / 1000) else ""), "🏁", p))
        }
        for (r in stillDriving) body.addView(standingRow("", r, if (r.stale) L.t("no signal") else NavEngine.formatDistance(r.left.coerceAtLeast(0.0)) + " " + L.t("to go") +
            (if (r.eta > 0) " · ~" + NavEngine.clock(r.eta) else ""), "${r.kmh} km/h", p))
        body.addView(label(if (stillDriving.isEmpty()) "Everyone's in." else "Stays open until everyone's in, then shows the final order.", 13f).apply {
            setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p, 0, 0)
        })
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
            addView(bigButton(L.t("Done")) { closeSheet() }, LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f))
            addView(pill("Leave") { confirmLeaveConvoy() }.apply { textSize = 14f; setTextColor(readable(Color.rgb(230, 70, 70))) },
                LinearLayout.LayoutParams(WRAP_CONTENT, (52 * d).toInt()).apply { marginStart = p / 2 })
        })
    }

    private fun fmtGap(s: Long) = if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"

    private fun standingRow(place: String, r: Convoy.Row, sub: String, right: String, p: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 2, p / 3, p / 2, p / 3)
        if (r.me) background = bg(dim(26), radius = 10f)
        addView(label(place, 15f, bold = true).apply { setTextColor(readable(accent)) }, LinearLayout.LayoutParams((20 * d).toInt(), WRAP_CONTENT))
        addView(dot(if (r.me) Convoy.myName.ifBlank { "You" } else r.name, r.color, 32, r.stale))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(if (r.me) L.t("You") else r.name, 15.5f, bold = true).apply { setTextColor(ink) })
            addView(label(sub, 12.5f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label(right, 14f, bold = true).apply { setTextColor(readable(Color.rgb(61, 220, 132))) })
    }

    /** Not in a convoy: Start one, Join one, or invite a friend who's online. */
    private fun renderConvoyStart(p: Int) {
        val cbody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.75).apply { addView(cbody) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        cbody.addView(label("Drive somewhere together and see each other on the map.", 14f).apply { setTextColor(dim()); setPadding(p / 4, p / 2, 0, p) })
        fun big(icon: String, title: String, sub: String, primary: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p + p / 3, p, p, p); isClickable = true
            background = if (primary) bg(accent, radius = 16f) else bg(dim(22), accent, 1.5f, 16f)
            setOnClickListener { onClick() }
            val fg = if (primary) onAccent(accent) else ink
            addView(label(icon, 24f).apply { gravity = Gravity.CENTER; setTextColor(fg) }, LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 17f, bold = true).apply { setTextColor(fg); typeface = theme.typeface })
                addView(label(sub, 13.5f).apply { setTextColor(if (primary) fg else dim()) }) // (faded, it was too faint on bright accents)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("›", 22f).apply { setTextColor(fg) })
        }
        cbody.addView(big("🏁", L.t("Start a convoy"), L.t("Pick where you're going, then invite people. Race or drive together: you choose next."), true) {
            // step 1 (Where) opens in the sheet: search is one of the choices there, not a jump
            withConvoyName { Convoy.create(null); setConvoyModeChosen(false); pendingMode = null; convoyKey = ""; openSheet("convoy"); renderConvoy() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        cbody.addView(big("▦", L.t("Join one"), L.t("Type a code, or scan the QR with your camera"), false) {
            convoyJoinOpen = !convoyJoinOpen; convoyKey = ""; renderConvoy()
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p * 2 / 3 })
        if (convoyJoinOpen) {
            lateinit var joinBtn: TextView
            val input = EditText(this).apply {
                hint = "K7P 2QX"; setSingleLine(); setTextColor(ink); setHintTextColor(dim(110))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                textSize = 28f; letterSpacing = 0.18f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
                background = bg(dim(22), dim(90), 1.5f, 14f)
                val q = (12 * d).toInt(); setPadding(q, q, q, q)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
                setOnEditorActionListener { v, _, _ -> joinConvoy(v.text.toString()); true }
                addTextChangedListener(object : android.text.TextWatcher {
                    private var busy = false
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(e: android.text.Editable) {
                        if (busy) return
                        val raw = e.toString()
                        // a pasted link stays as it is; a typed code is shown as "K7P 2QX"
                        if (!raw.contains("/") && !raw.contains(".")) {
                            val code = raw.uppercase().filter { it.isLetterOrDigit() }.take(6)
                            val pretty = if (code.length > 3) code.take(3) + " " + code.drop(3) else code
                            if (pretty != raw) { busy = true; e.replace(0, e.length, pretty); busy = false }
                        }
                        val ready = raw.contains("/") || raw.filter { it.isLetterOrDigit() }.length >= 6
                        joinBtn.alpha = if (ready) 1f else 0.45f; joinBtn.isEnabled = ready
                    }
                })
            }
            cbody.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p * 2 / 3 })
            joinBtn = pill("Join") { joinConvoy(input.text.toString()) }.apply {
                setTextColor(onAccent(accent)); background = bg(accent, radius = 14f); alpha = 0.45f; isEnabled = false
            }
            cbody.addView(joinBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
            cbody.addView(pill("Paste an invite link") {
                val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                if (clip.isNullOrBlank()) Toast.makeText(this, L.t("Nothing to paste"), Toast.LENGTH_SHORT).show() else joinConvoy(clip)
            }.apply { textSize = 14f; setTextColor(readable(accent)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            cbody.addView(label("Got a QR code? Point your phone's camera at it: GameRoute opens and joins.", 12.5f).apply {
                setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p / 3, 0, 0)
            })
            input.requestFocus()
        }
        // friends online: Invite starts a convoy for you and sends it
        val online = onlineFriends()
        if (online.isNotEmpty()) {
            cbody.addView(sectionTitle(L.t("FRIENDS ONLINE")))
            for (f in online.take(4)) cbody.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 3, 0, p / 3)
                addView(avatar(f.name, f.fid, true, 36), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
                addView(label(f.name, 16f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(pill("Invite") { inviteFriend(f); openSheet("convoy") }.apply {
                    textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f)
                })
            })
            cbody.addView(label("Invite starts a convoy for you and sends it to them.", 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p / 3, 0, 0) })
        }
        cbody.addView(label(L.t("You appear as \"${Convoy.myName.ifBlank { "Driver" }}\"") + " · " + L.t("Change"), 13f, bold = true).apply {
            setTextColor(readable(accent)); setPadding(p / 4, p, 0, 0); setOnClickListener { editConvoyName() }
        })
    }

    private var inviteKey = ""

    /** Invite: the QR and code (people next to you), the link (a message), and your friends. */
    private fun renderConvoyInvite() {
        if (!Convoy.active) { openSheet("convoy"); return }
        val c = if (Friends.ready && Friends.core.started) Friends.core else null
        val key = "${Convoy.code}|${theme.name}|${MapState.dark}|${c?.friends?.joinToString { it.fid + c.isOnline(it.fid) }}|${c?.sent?.joinToString { it.fid + it.status }}|${Convoy.members.values.joinToString { it.fid }}"
        if (key == inviteKey && sheetHost.childCount > 0) return
        inviteKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("Invite", sheetTitle(sheetStack.lastOrNull() ?: "convoy") to { if (sheetStack.isEmpty()) switchSheet("convoy") else sheetBack() }))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.72).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val p = (12 * d).toInt()
        val link = Convoy.link() ?: return
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p, p, p); background = bg(dim(22), radius = 16f)
            val side = (128 * d).toInt()
            addView(android.widget.ImageView(context).apply {
                setImageBitmap(qr(link, side)); scaleType = android.widget.ImageView.ScaleType.CENTER
                background = bg(Color.WHITE, radius = 10f); contentDescription = L.t("QR code for the convoy ${Convoy.pretty()}, tap to make it bigger")
                setOnClickListener { showConvoyQr() }
            }, LinearLayout.LayoutParams(side, side).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("CODE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.1f })
                addView(label(Convoy.pretty() ?: "", 26f, bold = true).apply { setTextColor(ink); letterSpacing = 0.12f; typeface = Typeface.MONOSPACE; maxLines = 1 })
                addView(pill("🔗 Share link") { inviteToConvoy() }.apply {
                    textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f)
                }, LinearLayout.LayoutParams(MATCH_PARENT, (42 * d).toInt()).apply { topMargin = p / 2 })
                addView(pill("Copy code") {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("Convoy code", Convoy.pretty()))
                    Toast.makeText(this@MainActivity, L.t("Code copied"), Toast.LENGTH_SHORT).show()
                }.apply { textSize = 13.5f; setTextColor(readable(accent)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        body.addView(label("Anyone next to you can scan the QR with their camera (tap it to make it bigger).", 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p / 2, 0, 0) })
        body.addView(sectionTitle(L.t("FRIENDS")))
        val friends = c?.friends.orEmpty().sortedByDescending { c?.isOnline(it.fid) == true }
        if (friends.isEmpty()) {
            body.addView(label("No friends yet. Add the people you drive with, then invite them in one tap.", 14f).apply { setTextColor(dim()) })
            body.addView(pill("＋ Add a friend") { openSheet("addfriend") }.apply { textSize = 15f; setTextColor(readable(accent)) })
        }
        val inIt = Convoy.members.values.map { it.fid }.toSet()
        for (f in friends) {
            val on = c?.isOnline(f.fid) == true
            val sent = c?.sent?.lastOrNull { it.fid == f.fid && it.convoy == Convoy.code }
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 3, 0, p / 3)
                addView(avatar(f.name, f.fid, on, 36), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(f.name, 16f, bold = true).apply { setTextColor(ink) })
                    addView(label(if (on) L.t("online") else L.t("offline"), 13f).apply { setTextColor(if (on) readable(Color.rgb(47, 180, 100)) else dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                when {
                    f.fid in inIt -> addView(label("In the convoy", 13.5f, bold = true).apply { setTextColor(dim()) })
                    sent != null -> addView(label(when (sent.status) { "seen" -> "Sent · seen"; "joined" -> "Joined"; "no" -> "Not now"; else -> "Sent" }, 13.5f, bold = true).apply {
                        setTextColor(readable(Color.rgb(255, 176, 40)))
                    })
                    else -> addView(pill("Invite") { inviteFriend(f); inviteKey = "" }.apply {
                        textSize = 14f
                        if (on) { setTextColor(onAccent(accent)); background = bg(accent, radius = 12f) }
                        else { setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 12f) }
                    })
                }
            })
        }
        if (friends.isNotEmpty()) body.addView(label("Offline friends get the invite the next time they open GameRoute (it expires after 2 h).", 12.5f).apply {
            setTextColor(dim()); setPadding(p / 4, p / 2, 0, 0)
        })
    }

    // ------------------------------------------------------------------ friends

    /** The You button: your level as a ring, with a dot when something's waiting. */
    private inner class YouRing(ctx: android.content.Context, val big: Boolean = false) : View(ctx) {
        private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val icon = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_i_person)?.mutate()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = minOf(w, h) / 2 - 3 * d; val cx = w / 2; val cy = h / 2
            val (have, of) = Explorer.progress()
            pt.style = android.graphics.Paint.Style.FILL; pt.color = dim(36); c.drawCircle(cx, cy, r, pt)
            pt.style = android.graphics.Paint.Style.STROKE; pt.strokeWidth = (if (big) 5f else 3.5f) * d; pt.strokeCap = android.graphics.Paint.Cap.ROUND
            pt.color = dim(60); c.drawCircle(cx, cy, r, pt)
            pt.color = accent; c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * have / maxOf(of, 1), false, pt)
            // my avatar inside the ring (the plain person until one is chosen)
            val mine = Avatars.byId(Avatars.mine)
            if (mine != null) Avatars.draw(c, mine, if (Avatars.myColor != 0) Avatars.myColor else mine.color, cx, cy, r - (if (big) 4.5f else 3f) * d)
            else icon?.let { ic ->
                val s = (r * 1.0f).toInt()
                ic.setTint(ink); ic.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt()); ic.draw(c)
            }
            // the level, small, bottom right
            pt.style = android.graphics.Paint.Style.FILL
            val lv = "${Explorer.level}"
            pt.textSize = (if (big) 13f else 10f) * d; pt.typeface = Typeface.DEFAULT_BOLD; pt.textAlign = android.graphics.Paint.Align.CENTER
            val bw = maxOf(pt.measureText(lv) + 8 * d, (if (big) 22f else 17f) * d); val bh = (if (big) 19f else 15f) * d
            val bx = w - bw / 2 - 1 * d; val by = h - bh / 2 - 1 * d
            pt.color = accent; c.drawRoundRect(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2, bh / 2, bh / 2, pt)
            pt.color = onAccent(accent); c.drawText(lv, bx, by + pt.textSize * 0.36f, pt)
            // something waiting (a friend request, an invite): a dot top right
            if (!big && friendsWaiting() > 0) {
                pt.color = Color.rgb(72, 199, 116); c.drawCircle(w - 6 * d, 6 * d, 5.5f * d, pt)
                pt.style = android.graphics.Paint.Style.STROKE; pt.strokeWidth = 2 * d; pt.color = opaque(panel); c.drawCircle(w - 6 * d, 6 * d, 5.5f * d, pt)
            }
        }
    }

    private var youKey = ""

    /** You: progress, Garage, friends, trips, and the app's settings, in one place. */
    /** Green Online, red Do not disturb, grey Invisible. */
    private fun statusColor(s: String) = when (s) { FriendCore.DND -> Color.rgb(240, 82, 82); FriendCore.INVISIBLE -> Color.rgb(140, 148, 160); else -> Color.rgb(64, 200, 120) }
    private fun statusName(s: String) = L.t(when (s) { FriendCore.DND -> "Do not disturb"; FriendCore.INVISIBLE -> "Invisible"; else -> "Online" })

    /** "● Online ▾": tap to change. */
    private fun statusChip(s: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val col = statusColor(s)
        setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
        background = bg(Color.TRANSPARENT, col, 1.5f, 16f)
        minimumHeight = (32 * d).toInt()
        addView(View(context).apply { background = bg(col, radius = 5f) }, LinearLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt()).apply { marginEnd = (7 * d).toInt() })
        addView(label(statusName(s), 14.5f, bold = true).apply { setTextColor(ink) })
        addView(label(" ▾", 13f).apply { setTextColor(dim()) })
        isClickable = true; setOnClickListener { onClick() }
        contentDescription = L.t("Your status") + ": " + statusName(s)
    }

    /** Online / Do not disturb / Invisible, and when to go back to Online by itself. */
    private fun chooseStatus() {
        if (!Friends.ready) return
        val keys = arrayOf(FriendCore.ONLINE, FriendCore.DND, FriendCore.INVISIBLE)
        val opts = arrayOf(
            L.t("Online") + "\n" + L.t("Friends see you online. Requests and convoy invites arrive straight away."),
            L.t("Do not disturb") + "\n" + L.t("Friends see you as busy. Requests and invites wait silently until you switch back."),
            L.t("Invisible") + "\n" + L.t("You look offline to everyone. Invites to you aren't delivered. You can still see friends and invite them."))
        GameBuilder(this@MainActivity).setTitle(L.t("Your status"))
            .setDots(intArrayOf(Color.rgb(76, 211, 138), Color.rgb(255, 92, 92), Color.rgb(138, 143, 153)))
            .setSingleChoiceItems(opts, keys.indexOf(Friends.core.status).coerceAtLeast(0)) { dlg, k ->
                dlg.dismiss()
                if (keys[k] == FriendCore.ONLINE) { Friends.setStatus(FriendCore.ONLINE); youKey = ""; refreshChrome(); return@setSingleChoiceItems }
                // back to Online after…
                val now = System.currentTimeMillis()
                val tonight = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59) }.timeInMillis
                val untils = longArrayOf(now + 3_600_000L, if (tonight > now + 600_000) tonight else now + 86_400_000L, 0L)
                GameBuilder(this@MainActivity).setTitle(L.t("Back to Online after"))
                    .setItems(arrayOf(L.t("1 hour"), L.t("Tonight"), L.t("Never (until I change it)"))) { _, u ->
                        Friends.setStatus(keys[k], untils[u]); youKey = ""; refreshChrome()
                    }.showT()
            }.showT()
    }

    private fun renderYou() {
        val c = if (Friends.ready && Friends.core.started) Friends.core else null
        val key = "${Convoy.myName}|${Explorer.xp}|${Xp.prestige}|${theme.name}|${MapState.dark}|${Xp.streak}|${c?.status}|${c?.friends?.joinToString { it.fid + c.isOnline(it.fid) + c.isBusy(it.fid) + c.nickname(it.fid) }}|" +
            "${c?.requests?.size}|${c?.invites?.size}|${Xp.challenges().joinToString { "${it.have}" }}|${Offline.areas().size}"
        if (key == youKey && sheetHost.childCount > 0) return
        youKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        val p = (12 * d).toInt()
        val (have, of) = Explorer.progress()
        // who I am: the ring, my title, level and streak
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, p / 2)
            sheetStack.lastOrNull()?.let { from -> addView(backPill(sheetTitle(from)) { sheetBack() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p / 2 }) }
            addView(YouRing(context, big = true).apply { setOnClickListener { chooseAvatar() }; contentDescription = L.t("Your avatar") }, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // your name first (tap to change it), then your title in this style, then the level
                addView(label(Convoy.myName.ifBlank { L.t("Your name") }, 24f, bold = true).apply {
                    setTextColor(if (Convoy.myName.isBlank()) dim() else ink); typeface = theme.typeface; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    contentDescription = L.t("Your name, tap to change it")
                    setOnClickListener { editConvoyName { youKey = ""; refreshChrome() } }
                })
                addView(label(Xp.stars + L.t(Xp.title()), 14.5f, bold = true).apply { setTextColor(readable(accent)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, (2 * d).toInt(), 0, 0) }.rankMark())
                addView(label(L.t("Level ${Explorer.level}") + " · $have / $of XP", 13.5f).apply { setTextColor(dim()) })
                if (Xp.streak > 0) addView(label("🔥 " + L.t(if (Xp.streak == 1) "1-day streak" else "${Xp.streak}-day streak"), 13.5f, bold = true).apply { setTextColor(readable(Color.rgb(255, 176, 40))) })
                // your status: who can reach you (Online / Do not disturb / Invisible)
                if (c != null) addView(statusChip(c.status) { chooseStatus() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (6 * d).toInt() })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("✕") { closeSheet() }.apply { setTextColor(ink) })
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.7).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // four tiles
        val waiting = friendsWaiting()
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p / 2, 0, 0)
            listOf(Triple("✓", "Progress", "progress"), Triple("🏠", "Garage", "garage"), Triple("👥", "Friends", "friends"), Triple("🕘", "Trips", "trips")).forEachIndexed { i, (ic, t, k) ->
                addView(android.widget.FrameLayout(context).apply {
                    background = bg(dim(22), if (k == "friends" && waiting > 0) accent else dim(60), if (k == "friends" && waiting > 0) 1.8f else 1f, 14f)
                    isClickable = true; setOnClickListener { openSheet(k) }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, p * 3 / 4, 0, p * 2 / 3)
                        addView(label(ic, 22f).apply { setTextColor(readable(accent)); gravity = Gravity.CENTER }, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
                        addView(label(t, 14f, bold = true).apply { setTextColor(ink); setPadding(0, p / 3, 0, 0); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    }, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    if (k == "friends" && waiting > 0) addView(label("$waiting", 11.5f, bold = true).apply {
                        setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = bg(Color.rgb(230, 60, 60), radius = 9f)
                    }, android.widget.FrameLayout.LayoutParams((20 * d).toInt(), (18 * d).toInt(), Gravity.END or Gravity.TOP).apply { topMargin = (5 * d).toInt(); rightMargin = (5 * d).toInt() })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i < 3) marginEnd = p / 2 })
            }
        })
        // friends: what's waiting first, then everyone as circles
        if (c != null) {
            val online = c.friends.count { c.isOnline(it.fid) }
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(sectionTitle(L.t("FRIENDS") + if (online > 0) " · " + L.t("$online ONLINE") else ""), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (c.friends.isNotEmpty()) addView(label(L.t("See all ${c.friends.size}") + " ›", 14f, bold = true).apply {
                    setTextColor(readable(accent)); setPadding(p / 2, (14 * d).toInt(), p / 3, 0); maxLines = 1; setOnClickListener { openSheet("friends") }
                })
            })
            // waiting: a card each, the text on top and the buttons under it (fits any text size)
            fun waitingCard(avatarOf: Pair<String, String>?, title: String, sub: String, no: () -> Unit, yesText: String, yes: () -> Unit) = body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(p, p * 2 / 3, p, p * 2 / 3); background = bg(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, 1.5f, 14f)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    avatarOf?.let { (n, id) -> addView(avatar(n, id, false, 38), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p }) }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(title, 15.5f, bold = true).apply { setTextColor(ink) })
                        addView(label(sub, 12.5f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, p / 2, 0, 0)
                    addView(pill("Not now") { no() }.apply { textSize = 14f; setTextColor(dim()); minHeight = (40 * d).toInt(); val q = (10 * d).toInt(); setPadding(q + q / 2, 0, q + q / 2, 0) },
                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p / 2 })
                    addView(pill(yesText) { yes() }.apply {
                        textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 12f)
                        minHeight = (40 * d).toInt(); val q = (10 * d).toInt(); setPadding(q * 2, 0, q * 2, 0)
                    })
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
            for (rq in c.requests.take(2)) waitingCard(rq.name to rq.fid, L.t("${rq.name} wants to be friends"), since(rq.at), { Friends.decline(rq) }, "Accept") { Friends.accept(rq) }
            for (inv in c.invites.filter { it.convoy != Convoy.code }.take(1)) waitingCard(null, L.t("${inv.from} invites you to a convoy"), inv.dest.ifBlank { L.t("No destination yet") },
                { Friends.answer(inv.convoy, "no") }, "Join") { closeSheet(); joinConvoy(inv.convoy) }
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                for (f in c.friends.sortedByDescending { c.isOnline(it.fid) }.take(4)) {
                    val on = c.isOnline(f.fid)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        isClickable = true; setOnClickListener { showFriendCard(f) }
                        addView(avatar(c.shownName(f), f.fid, on, 46), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
                        addView(label(c.shownName(f), 13.5f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                        val busy = on && c.isBusy(f.fid)
                        addView(label(if (busy) L.t("busy") else if (on) L.t("online") else L.t("offline"), 11.5f).apply { setTextColor(if (busy) readable(Color.rgb(230, 70, 70)) else if (on) readable(Color.rgb(47, 180, 100)) else dim()); gravity = Gravity.CENTER })
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true; setOnClickListener { openSheet("addfriend") }
                    addView(label("＋", 20f).apply {
                        gravity = Gravity.CENTER; setTextColor(readable(accent))
                        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setStroke((1.5f * d).toInt(), accent, 5 * d, 4 * d) }
                    }, LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
                    addView(label("Add", 13.5f, bold = true).apply { setTextColor(ink); gravity = Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, 0) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                repeat((4 - c.friends.size).coerceAtLeast(0)) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 3 })
            if (c.friends.isNotEmpty()) body.addView(label("Tap a friend to invite them to a convoy.", 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p / 2, 0, 0) })
        }
        // this week: the next challenge
        Xp.challenges().firstOrNull { !it.done }?.let { ch ->
            body.addView(sectionTitle(L.t("THIS WEEK")))
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(p, p * 2 / 3, p, p * 2 / 3); background = bg(dim(20), radius = 12f)
                isClickable = true; setOnClickListener { openSheet("progress") }
                addView(label(L.t(ch.title), 15.5f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(label("${ch.have}/${ch.of} · ${ch.reward} XP", 14f, bold = true).apply { setTextColor(readable(Color.rgb(255, 176, 40))) })
            })
        }
        // the app
        body.addView(sectionTitle(L.t("APP")))
        fun row(icon: Int, title: String, sub: String?, onClick: () -> Unit) = body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 4, p * 2 / 3, 0, p * 2 / 3)
            isClickable = true; setOnClickListener { onClick() }
            addView(android.widget.ImageView(context).apply {
                setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(readable(accent))
                val q = (7 * d).toInt(); setPadding(q, q, q, q); background = bg(Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent)), radius = 10f)
            }, LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 16f, bold = true).apply { setTextColor(ink) })
                if (sub != null) addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("›", 20f).apply { setTextColor(dim()) })
        })
        row(R.drawable.ic_set_map, "Offline maps", Offline.areas().let { a -> if (a.isEmpty()) L.t("Download regions and roads") else "${a.size} · ${Offline.size(a.sumOf { it.bytes })}" }) { openSheet("offline") }
        row(R.drawable.ic_theme, "Map style", L.t(theme.title)) { openSheet("style") }
        row(R.drawable.ic_set_app, "Settings", null) { showSettings() }
        row(R.drawable.ic_set_adv, "Help & about", null) { showAbout() }
    }

    private var friendsKey = ""

    /** Requests and invites waiting (not the convoy I'm in already). */
    private fun friendsWaiting(): Int {
        if (!Friends.ready || !Friends.core.started) return 0
        return Friends.core.requests.size + Friends.core.invites.count { it.convoy != Convoy.code }
    }

    private fun onlineFriends(): List<FriendCore.Friend> =
        if (!Friends.ready || !Friends.core.started) emptyList() else Friends.core.friends.filter { Friends.core.isOnline(it.fid) }

    private fun updatePeopleBadge() {
        if (!::peopleBadge.isInitialized) return
        val n = friendsWaiting()
        peopleBadge.text = if (n > 9) "9+" else n.toString()
        peopleBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
        val online = Friends.ready && Friends.core.started && Friends.core.friends.any { Friends.core.isOnline(it.fid) }
        peopleDot.visibility = if (online) View.VISIBLE else View.GONE
        peopleBtn.contentDescription = L.t("Friends") + (if (n > 0) " · " + L.t("$n waiting") else "") + (if (online) " · " + L.t("friends online") else "")
    }

    /** The people button: Together (Friends first; the convoy if I'm in one and nothing's waiting). */
    private fun openTogether() = openSheet(if (Convoy.active && friendsWaiting() == 0) "convoy" else "friends")

    /** Convoy and Friends are one screen, "Together", with two tabs. */
    private fun togetherHeader(tab: String) {
        sheetHost.addView(sheetHeader("Together"))
        val p = (4 * d).toInt()
        val waiting = friendsWaiting()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(p, p, p, p); background = bg(dim(22), radius = 14f)
        }
        for ((key, title) in listOf("convoy" to "Convoy", "friends" to "Friends")) {
            val on = key == tab
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; minimumHeight = (44 * d).toInt()
                if (on) background = bg(accent, radius = 12f)
                isClickable = true; setOnClickListener { if (!on) switchSheet(key) }
                contentDescription = L.t(title)
                addView(label(if (key == "convoy") "👥 $title" else title, 15f, bold = true).apply { setTextColor(if (on) onAccent(accent) else ink) })
                if (key == "friends" && waiting > 0) addView(label(if (waiting > 9) "9+" else "$waiting", 11.5f, bold = true).apply {
                    setTextColor(Color.WHITE); gravity = Gravity.CENTER; minWidth = (20 * d).toInt()
                    val q = (5 * d).toInt(); setPadding(q, 0, q, 0); background = bg(Color.rgb(230, 60, 60), radius = 10f)
                }, LinearLayout.LayoutParams(WRAP_CONTENT, (20 * d).toInt()).apply { marginStart = (8 * d).toInt() })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (key == "convoy") marginEnd = p })
        }
        sheetHost.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * d).toInt(); bottomMargin = (6 * d).toInt() })
    }
    private val friendsListener: () -> Unit = { runOnUiThread { friendsKey = ""; refreshChrome() } }
    private val friendsArrived: () -> Unit = { runOnUiThread {
        // driving: friends' news waits (the request or invite is in Together when you stop)
        if (Later.driving && Friends.ready && Friends.core.started) {
            val req = Friends.core.requests.size
            if (req > 0) Later.add("👥 " + L.t(if (req == 1) "1 friend request: open Together" else "$req friend requests: open Together"))
        }
        maybeAskInvite()
    } }
    private var inviteAsked = ""

    private fun since(at: Long): String {
        val m = ((System.currentTimeMillis() - at) / 60_000).coerceAtLeast(0)
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; else -> "${m / 60} h ago" }
    }

    /** A round badge with someone's initial (their colour from their id). */
    private fun avatar(name: String, fid: String, online: Boolean, size: Int = 40) = android.widget.FrameLayout(this).apply {
        val pal = intArrayOf(Color.rgb(255, 146, 43), Color.rgb(77, 171, 247), Color.rgb(247, 131, 172), Color.rgb(99, 230, 190),
            Color.rgb(177, 151, 252), Color.rgb(255, 212, 59), Color.rgb(47, 210, 122))
        val col = pal[Math.floorMod(fid.hashCode(), pal.size)]
        // their avatar if they've chosen one (and aren't Invisible), else their initial
        val av = Avatars.ofFriend(fid)
        if (av != null) addView(AvatarView(context, av.first, av.second), android.widget.FrameLayout.LayoutParams((size * d).toInt(), (size * d).toInt()))
        else addView(label(name.trim().take(1).uppercase().ifBlank { "?" }, size * 0.4f, bold = true).apply {
            gravity = Gravity.CENTER; setTextColor(Color.rgb(17, 17, 17)); background = bg(col, radius = size / 2f)
        }, android.widget.FrameLayout.LayoutParams((size * d).toInt(), (size * d).toInt()))
        // green online, red Do not disturb (their invites wait)
        val busy = online && Friends.ready && Friends.core.isBusy(fid)
        if (online) addView(View(context).apply { background = bg(if (busy) Color.rgb(240, 82, 82) else Color.rgb(72, 199, 116), panel, 2f, 7f) },
            android.widget.FrameLayout.LayoutParams((13 * d).toInt(), (13 * d).toInt(), Gravity.END or Gravity.BOTTOM))
    }

    /** One avatar, drawn (a lock over it when [locked]). */
    private inner class AvatarView(ctx: android.content.Context, val a: Avatars.Avatar, var color: Int, val locked: Boolean = false, val chosen: Boolean = false) : View(ctx) {
        private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = minOf(width, height) / 2f - (if (chosen) 4 * d else 0f)
            Avatars.draw(c, a, color, width / 2f, height / 2f, r)
            if (chosen) { pt.style = android.graphics.Paint.Style.STROKE; pt.strokeWidth = 3 * d; pt.color = accent; c.drawCircle(width / 2f, height / 2f, r + 2.5f * d, pt) }
            if (locked) {
                pt.style = android.graphics.Paint.Style.FILL; pt.color = Color.argb(150, 10, 6, 20); c.drawCircle(width / 2f, height / 2f, r, pt)
                pt.color = Color.WHITE; pt.textSize = r * 0.7f; pt.textAlign = android.graphics.Paint.Align.CENTER
                c.drawText("🔒", width / 2f, height / 2f + r * 0.25f, pt)
            }
        }
    }

    /** You › tap your circle: pick an avatar and its colour (locked ones say what unlocks them). */
    private fun chooseAvatar() {
        val p = (12 * d).toInt()
        lateinit var dlg: GameDialog
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun build() {
            box.removeAllViews()
            val mine = Avatars.byId(Avatars.mine)
            val col = if (Avatars.myColor != 0) Avatars.myColor else mine?.color ?: Avatars.colors[1]
            box.addView(label(L.t("Friends see it in their list and in convoys"), 13.5f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, 0, 0, p) })
            var row: LinearLayout? = null
            Avatars.all.forEachIndexed { i, a ->
                if (i % 4 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }; box.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p }) }
                val open = Avatars.unlocked(a)
                val v = AvatarView(this, a, if (a.id == mine?.id) col else a.color, locked = !open, chosen = a.id == mine?.id).apply {
                    contentDescription = L.t(a.name) + if (open) "" else ", " + Avatars.lockText(a)
                    setOnClickListener {
                        if (!open) { Toast.makeText(this@MainActivity, L.t(a.name) + " · " + Avatars.lockText(a), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                        Avatars.mine = a.id; youKey = ""; youBtn.invalidate(); build()
                    }
                }
                row!!.addView(v, LinearLayout.LayoutParams(0, (66 * d).toInt(), 1f))
            }
            box.addView(label(L.t("BACKGROUND COLOUR"), 12.5f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.1f; setPadding(p / 2, p / 2, 0, p / 2) })
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                for (cc in Avatars.colors) addView(View(context).apply {
                    background = if (cc == col) bg(cc, Color.WHITE, 3f, 18f) else bg(cc, radius = 18f)
                    setOnClickListener { Avatars.myColor = cc; if (Avatars.mine.isBlank()) Avatars.mine = Avatars.all[5].id; youKey = ""; youBtn.invalidate(); build() }
                }, LinearLayout.LayoutParams(0, (36 * d).toInt(), 1f).apply { marginStart = p / 3; marginEnd = p / 3 })
            })
            Avatars.all.firstOrNull { !Avatars.unlocked(it) }?.let { next ->
                box.addView(label("🔒 " + L.t(next.name) + " · " + Avatars.lockText(next), 12.5f).apply { setTextColor(dim()); setPadding(p / 2, p, 0, 0) })
            }
            if (mine != null) box.addView(label(L.t("Use my initial instead"), 14f, bold = true).apply {
                setTextColor(readable(accent)); gravity = Gravity.CENTER; setPadding(0, p, 0, 0)
                setOnClickListener { Avatars.mine = ""; youKey = ""; youBtn.invalidate(); build() }
            })
            L.translateTree(box)
        }
        build()
        dlg = GameBuilder(this).setTitle("Your avatar").setView(box).setPositiveButton("Done") { _, _ -> refreshChrome() }.showT()
    }

    /**
     * A scrolling part of a sheet, at most [frac] of the screen, and less when the keyboard is up
     * (then the sheet sits above the keyboard and this part shrinks: what you type stays in view).
     */
    private inner class SheetScroll(private val frac: Double) : ScrollView(this@MainActivity) {
        override fun onMeasure(w: Int, h: Int) {
            val maxH = (resources.displayMetrics.heightPixels * frac).toInt()
            val lim = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) maxH else minOf(maxH, MeasureSpec.getSize(h))
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(lim, MeasureSpec.AT_MOST))
        }
    }

    private fun friendsBody(): LinearLayout {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.72).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return body
    }

    private fun friendsSection(body: LinearLayout, title: String) =
        body.addView(label(title.uppercase(), 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding((4 * d).toInt(), (16 * d).toInt(), 0, (4 * d).toInt()) })

    private fun shareFriendLink() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, Friends.shareText()) }, L.t("Send your friend link")))
    }

    /** Friends: my code, invites, requests, who's online, one tap to invite to a convoy. */
    private fun renderFriends() {
        Friends.start()
        val c = Friends.core
        val cv = Convoy.code
        val fr = c.friends.sortedWith(compareBy({ !c.isOnline(it.fid) }, { it.name.lowercase() }))
        val key = "${c.code}|${fr.joinToString { it.fid + it.name + c.isOnline(it.fid) + Friends.activityText(it.fid) + (cv?.let { x -> c.sentTo(it.fid, x)?.status }) }}|" +
            "${c.requests.joinToString { it.fid }}|${c.invites.joinToString { it.convoy + it.count }}|${c.pending.joinToString { it.nonce + it.status }}|$cv|${Convoy.active}|${theme.name}|${MapState.dark}"
        if (key == friendsKey && sheetHost.childCount > 0) return
        friendsKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        togetherHeader("friends")
        val p = (12 * d).toInt()
        val body = friendsBody()
        // my code, to give to someone
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(22), radius = 14f)
            addView(label("YOUR CODE", 11f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding(0, 0, p, 0) })
            addView(label(c.code, 20f, bold = true).apply {
                setTextColor(ink); letterSpacing = 0.06f; typeface = Typeface.MONOSPACE; maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(13, 20, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("Share") { shareFriendLink() }.apply { textSize = 14f; setTextColor(readable(accent)) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
        body.addView(pill("＋  Add a friend") { openSheet("addfriend") }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 22f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, (50 * d).toInt()).apply { topMargin = p })
        // convoy invites (current ones only; several to the same convoy are one)
        val inv = c.invites
        if (inv.isNotEmpty()) {
            friendsSection(body, "Invites")
            for (i in inv) body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p); background = bg(dim(22), accent, 1.5f, 14f)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(avatar(i.from, i.fid, c.isOnline(i.fid)), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(i.from + (if (i.dest.isNotBlank()) " · " + L.t("convoy to ${i.dest}") else " · " + L.t("convoy")), 15f, bold = true).apply { setTextColor(ink) })
                        addView(label((if (i.count > 1) L.t("${i.count} invites") + " · " + L.t("latest") + " " else "") + L.t(since(i.at)), 13f).apply { setTextColor(dim()) })
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
                    addView(pill("Join the convoy") { closeSheet(); joinConvoy(i.convoy) }.apply { textSize = 15f; setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f).apply { marginEnd = p })
                    addView(pill("Not now") { Friends.answer(i.convoy, "no") }.apply { textSize = 15f; setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
            Friends.markSeen(inv)
        }
        // requests to me
        if (c.requests.isNotEmpty()) {
            friendsSection(body, "Requests")
            for (r in c.requests) body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, p / 2)
                addView(avatar(r.name, r.fid, false), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(r.name, 15f, bold = true).apply { setTextColor(ink) })
                    addView(label("wants to add you", 13f).apply { setTextColor(dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(pill("Accept") { Friends.accept(r) }.apply { textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 12f) })
                addView(pill("✕") { Friends.decline(r) }.apply { textSize = 14f; setTextColor(ink); contentDescription = "Decline" })
            })
        }
        // friends: online first; Invite (or how far my invite to this convoy got)
        if (fr.isEmpty()) body.addView(label("No friends yet. Share your code, or add people you drove with in a convoy.", 14f).apply { setTextColor(dim()); setPadding(0, p, 0, 0) })
        // what Invite does: it starts a convoy (said once, above the list, while there isn't one)
        else if (!Convoy.active) body.addView(label("👥 Inviting a friend starts a convoy. Then you choose where you're going and how you drive.", 13.5f).apply {
            setTextColor(dim()); setPadding(p / 4, p * 2 / 3, p / 4, 0)
        })
        // just started one by inviting: the next step, one tap away
        else if (Convoy.host && Convoy.destination == null && !Convoy.meSharing) body.addView(label("👥 Convoy started: choose where you're going ›", 14f, bold = true).apply {
            setTextColor(readable(accent)); setPadding(p / 4, p * 2 / 3, p / 4, 0)
            setOnClickListener { openSheet("convoy") }
        })
        val on = fr.filter { c.isOnline(it.fid) }; val off = fr - on.toSet()
        for ((title, list) in listOf("Online · ${on.size}" to on, "Offline" to off)) {
            if (list.isEmpty()) continue
            friendsSection(body, title)
            for (f in list) body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, p / 2)
                isClickable = true; setOnClickListener { showFriendCard(f) }
                val online = c.isOnline(f.fid)
                addView(avatar(f.name, f.fid, online), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(c.shownName(f), 15f, bold = true).apply { setTextColor(ink) })
                    Friends.levelOf(f.fid).takeIf { it.isNotBlank() }?.let { addView(label(it, 12.5f, bold = true).apply { setTextColor(readable(accent)) }) }
                    val busy = online && c.isBusy(f.fid)
                    addView(label(Friends.activityText(f.fid), 13f).apply { setTextColor(if (busy) readable(Color.rgb(230, 70, 70)) else if (online) readable(Color.rgb(47, 180, 100)) else dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                val st = cv?.let { c.sentTo(f.fid, it)?.status }
                if (st != null) addView(label(when (st) { "seen" -> "✓✓ Seen"; "joined" -> "✓ Joined"; "no" -> "Not now"; else -> "✓ Sent" }, 13.5f, bold = true).apply {
                    setTextColor(when (st) { "joined" -> readable(Color.rgb(47, 180, 100)); "seen" -> readable(Color.rgb(77, 140, 230)); else -> dim() }); setPadding(p, 0, p / 2, 0)
                    if (st == "no") setOnClickListener { inviteFriend(f) } // ask again
                })
                else addView(pill("👥 Invite") { inviteFriend(f) }.apply { textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f) })
            })
        }
        if (c.pending.isNotEmpty()) {
            friendsSection(body, "Requests you sent")
            for (q in c.pending) body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((4 * d).toInt(), p / 3, 0, p / 3)
                addView(label(q.name.ifBlank { FriendCore.pretty(q.code) }, 14.5f, bold = true).apply { setTextColor(ink) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(label(when (q.status) { "accepted" -> "✓ Accepted"; "declined" -> "Declined"; else -> L.t("Waiting for an answer") + "…" }, 13.5f, bold = q.status != "waiting").apply {
                    setTextColor(when (q.status) { "accepted" -> readable(Color.rgb(47, 180, 100)); "declined" -> readable(Color.rgb(230, 80, 80)); else -> dim() })
                })
            })
        }
        body.addView(label("Your location is never shared with friends: only inside a convoy you join.", 12f).apply { setTextColor(dim()); setPadding(0, p, 0, 0) })
    }

    /** Add a friend: my QR and link, their code, or someone I drove with. */
    private fun renderAddFriend() {
        val c = Friends.core
        val recent = c.recent.filter { !c.isFriend(it.fid) && it.fid !in c.blocked }.reversed()
        val key = "add|${c.code}|${recent.joinToString { it.fid + c.requested(it.code) }}|${theme.name}|${MapState.dark}"
        if (key == friendsKey && sheetHost.childCount > 0) return
        friendsKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("Add a friend"))
        val p = (12 * d).toInt()
        val body = friendsBody()
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p, p, p); background = bg(dim(22), radius = 14f)
            addView(android.widget.ImageView(context).apply {
                setImageBitmap(qr(Friends.link(), (128 * d).toInt())); contentDescription = L.t("QR code for ${c.code}")
                // (the white border is in the code itself; no scaling, sharp modules)
                background = bg(Color.WHITE, radius = 8f); scaleType = android.widget.ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams((128 * d).toInt(), (128 * d).toInt()).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(c.code, 19f, bold = true).apply { setTextColor(ink); typeface = Typeface.MONOSPACE; letterSpacing = 0.05f })
                addView(label("Let a friend scan this with their camera, or send them the link.", 13f).apply { setTextColor(dim()); setPadding(0, p / 3, 0, p / 2) })
                addView(pill("Send link") { shareFriendLink() }.apply { textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 12f) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
        friendsSection(body, "Or type their code")
        lateinit var add: TextView
        val input = EditText(this).apply {
            hint = "NIKOS-2290"; setSingleLine(); setTextColor(ink); setHintTextColor(dim(110))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 22f; letterSpacing = 0.1f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
            background = bg(dim(22), dim(90), 1.5f, 14f); val q = (10 * d).toInt(); setPadding(q, q, q, q)
            addTextChangedListener(object : android.text.TextWatcher {
                private var busy = false
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
                override fun afterTextChanged(e: android.text.Editable) {
                    if (busy) return
                    val raw = e.toString()
                    if (!raw.contains("/")) {
                        // letters, then the 4 digits: "nikos2290" shows as "NIKOS-2290"
                        val k = FriendCore.norm(FriendCore.latin(raw)).take(10)
                        val pretty = FriendCore.pretty(k)
                        if (pretty != raw) { busy = true; e.replace(0, e.length, pretty); busy = false }
                    }
                    val ok = FriendCore.valid(e.toString())
                    add.alpha = if (ok) 1f else 0.45f; add.isEnabled = ok
                }
            })
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p })
            add = pill("Add") { sendFriendRequest(input.text.toString(), "") { input.setText("") } }.apply {
                setTextColor(onAccent(accent)); background = bg(accent, radius = 14f); alpha = 0.45f; isEnabled = false
            }
            addView(add)
        })
        if (recent.isNotEmpty()) {
            friendsSection(body, "You drove with")
            for (r in recent) body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, p / 2)
                addView(avatar(r.name, r.fid, false), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(r.name, 15f, bold = true).apply { setTextColor(ink) })
                    if (r.dest.isNotBlank()) addView(label(L.t("Convoy to ${r.dest}"), 13f).apply { setTextColor(dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (c.requested(r.code)) addView(label("Request sent", 13.5f).apply { setTextColor(dim()) })
                else addView(pill("Add") { sendFriendRequest(r.code, r.name) {} }.apply { textSize = 14f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 12f) })
            })
        }
        body.addView(label("They get a request and choose Accept or Decline. Nothing is shared until they accept.", 12.5f).apply { setTextColor(dim()); setPadding(0, p, 0, 0) })
    }

    /** A QR code (black on white, so every camera reads it, in every theme). */
    /**
     * A QR code that cameras read: black on white with the 4-module white border scanners need
     * (without it many don't see the code at all), whole pixels per module (sharp edges).
     */
    private fun qr(text: String, size: Int): android.graphics.Bitmap = qrBitmap(text, size)

    private fun sendFriendRequest(code: String, name: String, then: () -> Unit) {
        Friends.start()
        val k = FriendCore.norm(code)
        when {
            k == FriendCore.norm(Friends.core.code) -> Toast.makeText(this, L.t("That's your own code"), Toast.LENGTH_SHORT).show()
            Friends.core.friends.any { FriendCore.norm(it.code) == k } -> Toast.makeText(this, L.t("You're friends already"), Toast.LENGTH_SHORT).show()
            else -> withConvoyName {
                if (Friends.request(k, name)) {
                    Toast.makeText(this, L.t("Friend request sent") + (if (name.isNotBlank()) " · $name" else ""), Toast.LENGTH_SHORT).show()
                    then()
                } else Toast.makeText(this, L.t("That isn't a friend code"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** gamemap://friend/<code>, from a friend's link or QR. */
    private fun addFriendFromLink(code: String) {
        val k = FriendCore.norm(code)
        if (!FriendCore.valid(k)) { Toast.makeText(this, L.t("That isn't a friend code"), Toast.LENGTH_SHORT).show(); return }
        Friends.start()
        if (k == FriendCore.norm(Friends.core.code)) { Toast.makeText(this, L.t("That's your own code"), Toast.LENGTH_SHORT).show(); return }
        GameBuilder(this@MainActivity).setTitle("Add a friend?")
            .setMessage(L.t("Send a friend request to ${FriendCore.pretty(k)}?") + "\n\n" + L.t("They choose Accept or Decline. Your location is never shared with friends."))
            .setPositiveButton("Send request") { _, _ -> sendFriendRequest(k, "") { openSheet("friends") } }
            .setNegativeButton("Cancel", null).showT()
    }

    /** Invites a friend to my convoy (starts one first if I'm not in one: going where I'm going). */
    private fun inviteFriend(f: FriendCore.Friend) {
        withConvoyName {
            val started = !Convoy.active
            if (started) {
                val dest = if (NavEngine.active && !NavEngine.arrived) NavEngine.destination?.let { Place(NavEngine.destinationName, "", it) } else NavEngine.planPlace
                Convoy.create(dest)
            }
            Friends.invite(f)
            Toast.makeText(this, if (started) L.t("Convoy started · invite sent to ${f.name}") else L.t("Invite sent to ${f.name}"), Toast.LENGTH_SHORT).show()
            friendsKey = ""; refreshChrome()
        }
    }

    /** Tap a friend: invite, remove or block (no location: friends only see each other in a convoy). */
    private fun showFriendCard(f: FriendCore.Friend) {
        val p = (16 * d).toInt()
        lateinit var dlg: GameDialog
        val online = Friends.core.isOnline(f.fid)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p + p / 2, p, p + p / 2, p / 2) }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(avatar(f.name, f.fid, online, 54), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(Friends.core.shownName(f), 22f, bold = true).apply { setTextColor(ink); typeface = theme.typeface })
                if (Friends.core.nickname(f.fid).isNotBlank()) addView(label(L.t("Calls themselves ${f.name}"), 12.5f).apply { setTextColor(dim()) })
                Friends.levelOf(f.fid).takeIf { it.isNotBlank() }?.let { addView(label(it, 14f, bold = true).apply { setTextColor(readable(accent)) }) }
                addView(label(if (online) "Online" else "Offline", 14f, bold = online).apply { setTextColor(if (online) readable(Color.rgb(47, 180, 100)) else dim()) })
            })
        })
        val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(f.since))
        val drives = Friends.core.drivesWith(f.fid)
        box.addView(label(L.t("Friends since") + " " + date + (if (drives > 0) " · " + L.t(if (drives == 1) "1 drive together" else "$drives drives together") else ""), 13.5f).apply { setTextColor(dim()); setPadding(0, p, 0, p) })
        box.addView(pill(if (Convoy.active) "👥 Invite to your convoy" else "👥 Start a convoy and invite") { dlg.dismiss(); inviteFriend(f) }.apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 16f) },
            LinearLayout.LayoutParams(MATCH_PARENT, (52 * d).toInt()))
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p * 3 / 4, 0, 0)
            addView(pill("Rename") {
                dlg.dismiss()
                val input = EditText(this@MainActivity).apply { setText(Friends.core.shownName(f)); setSingleLine(); selectAll() }
                GameBuilder(this@MainActivity).setTitle(L.t("Your name for ${f.name}")).setMessage(L.t("Only you see it."))
                    .setView(LinearLayout(this@MainActivity).apply { val q = (20 * d).toInt(); setPadding(q, 0, q, 0); addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) })
                    .setPositiveButton("Save") { _, _ -> Friends.core.setNickname(f.fid, input.text.toString().takeIf { it != f.name }.orEmpty()); friendsKey = ""; youKey = ""; refreshChrome() }
                    .setNegativeButton("Cancel", null).showT()
            }.apply { textSize = 14.5f; setTextColor(readable(accent)); background = bg(Color.TRANSPARENT, accent, 1.5f, 14f) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p / 2 })
            addView(pill("Remove") {
                dlg.dismiss()
                GameBuilder(this@MainActivity).setTitle(L.t("Remove ${f.name}?")).setMessage("You can add each other again later.")
                    .setPositiveButton("Remove") { _, _ -> Friends.remove(f) }.setNegativeButton("Cancel", null).showT()
            }.apply { textSize = 14.5f; setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p / 2 })
            addView(pill("Block") {
                dlg.dismiss()
                GameBuilder(this@MainActivity).setTitle(L.t("Block ${f.name}?")).setMessage("They're removed, and their requests and invites won't reach you. You can unblock them in Settings › Sharing.")
                    .setPositiveButton("Block") { _, _ -> Friends.block(f.fid, f.name) }.setNegativeButton("Cancel", null).showT()
            }.apply { textSize = 14.5f; setTextColor(readable(Color.rgb(230, 80, 80))); background = bg(Color.TRANSPARENT, Color.rgb(160, 70, 70), 1.5f, 14f) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        dlg = GameBuilder(this@MainActivity).setView(box).showT()
    }

    /** A friend invited me to a convoy: ask (not while driving: then the chip on the map shows it). */
    private fun maybeAskInvite() {
        if (!Friends.ready || !Friends.core.allowInvites || isFinishing) return
        if (Convoy.proposal != null) return
        val i = Friends.core.invites.firstOrNull { it.convoy != Convoy.code } ?: return
        val k = i.convoy + i.count
        if (k == inviteAsked) return
        inviteAsked = k
        Friends.cancelNotification(i.convoy)
        // the small card (out from under Where to?, or the turn box while driving)
        InvitePop.offer(i)
        Friends.markSeen(listOf(i))
    }

    // ---------------------------------------------------------------- the invite card

    private lateinit var inviteClip: FrameLayout
    private var inviteCard: View? = null
    private var inviteFor: FriendCore.Invite? = null

    private val invitePopListener: () -> Unit = { runOnUiThread { syncInvitePop() } }

    private fun syncInvitePop() {
        val i = InvitePop.current
        if (i == null) { hideInvitePop(); return }
        if (i === inviteFor && inviteCard != null) return
        showInvitePop(i)
    }

    /** Where the card comes out: under the turn box while driving, else under Where to?. */
    private fun inviteTop(): Int =
        if (NavEngine.active && !NavEngine.arrived) (map.bannerBottom + 6 * d).toInt()
        else (topArea.top + searchCard.bottom + 6 * d).toInt().coerceAtLeast((40 * d).toInt())

    private fun showInvitePop(i: FriendCore.Invite) {
        inviteClip.removeAllViews()
        inviteFor = i
        val p = (10 * d).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p, p, p + (3 * d).toInt())
            background = bg(opaque(panel), accent, 1.5f, 14f)
            isClickable = true
            setOnClickListener { InvitePop.close(); openTogether() }
            contentDescription = L.t("${i.from} invites you to a convoy")
            addView(avatar(i.from, i.fid, true, 40))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding((10 * d).toInt(), 0, (6 * d).toInt(), 0)
                addView(label(L.t("${i.from} invites you"), 15.5f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val sub = "🏁 " + (if (i.dest.isNotBlank()) L.t("Convoy to ${i.dest}") else L.t("A convoy")) +
                    (if (i.count > 1) " · " + L.t("${i.count} invites") else "") + (if (Convoy.active) " · " + L.t("you'll leave yours") else "")
                addView(label(sub, 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "✕"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(dim())
                contentDescription = L.t("Not now")
                background = bg(Color.TRANSPARENT, dim(110), 1.2f, 12f)
                setOnClickListener { InvitePop.notNow() }
            }, LinearLayout.LayoutParams((42 * d).toInt(), (42 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
            addView(pill(L.t("Join")) { InvitePop.take()?.let { joinConvoy(it.convoy) } }.apply {
                setTextColor(onAccent(accent)); background = bg(accent, radius = 12f); textSize = 15f
                minHeight = (42 * d).toInt(); setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, (42 * d).toInt()))
        }
        // the 10 s running down
        val barColor = accent
        val bar = object : View(this) {
            private val pt = android.graphics.Paint().apply { color = barColor }
            override fun onDraw(c: Canvas) {
                val left = InvitePop.left()
                c.drawRect(0f, 0f, width * left, height.toFloat(), pt)
                if (left > 0f && inviteCard != null) postInvalidateDelayed(100)
            }
        }
        val holder = FrameLayout(this).apply {
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, (3 * d).toInt(), Gravity.BOTTOM).apply {
                leftMargin = (8 * d).toInt(); rightMargin = (8 * d).toInt(); bottomMargin = (3 * d).toInt()
            })
        }
        inviteClip.addView(holder, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            leftMargin = (12 * d).toInt(); rightMargin = (12 * d).toInt()
        })
        (inviteClip.layoutParams as FrameLayout.LayoutParams).topMargin = inviteTop()
        inviteClip.requestLayout()
        inviteClip.visibility = View.VISIBLE
        inviteCard = holder
        // out from under the bar above it (the clip hides it until it slides down)
        holder.translationY = -200 * d
        holder.post {
            holder.translationY = -holder.height.toFloat()
            holder.animate().translationY(0f).setDuration(260).start()
        }
    }

    private fun hideInvitePop() {
        val v = inviteCard ?: return
        inviteCard = null; inviteFor = null
        v.animate().translationY(-v.height.toFloat()).setDuration(220).withEndAction {
            if (inviteCard == null) { inviteClip.removeAllViews(); inviteClip.visibility = View.GONE }
        }.start()
    }

    /** For tests: the card on screen now. */
    internal fun invitePopForTest(): View? = inviteCard

    /** Invite in the convoy: friends first, or the link for anyone else. */
    private fun showInviteMenu(anchor: View) {
        val p = (14 * d).toInt()
        lateinit var pop: android.widget.PopupWindow
        fun item(icon: String, title: String, sub: String?, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p * 3 / 4, p * 3 / 2, p * 3 / 4); minimumHeight = (54 * d).toInt(); isClickable = true
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(dim(60)), null, null)
            addView(label(icon, 18f).apply { setPadding(0, 0, p, 0) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 15f, bold = true).apply { setTextColor(ink) })
                if (sub != null) addView(label(sub, 12.5f).apply { setTextColor(dim()) })
            })
            setOnClickListener { pop.dismiss(); onClick() }
        }
        val c = Friends.core
        val online = c.friends.filter { c.isOnline(it.fid) }.map { it.name }
        val sub = when {
            c.friends.isEmpty() -> L.t("No friends yet: add some")
            online.isEmpty() -> L.t("${c.friends.size} friends, none online")
            online.size <= 2 -> L.t("${online.joinToString(" and ")} online")
            else -> L.t("${online.size} online")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(panel, accent, 1.5f, 14f); elevation = 16 * d
            setPadding(0, p / 3, 0, p / 3)
            addView(item("👥", "Invite friends", sub) { openSheet("friends") })
            addView(View(context).apply { setBackgroundColor(dim(50)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, d.toInt())).apply { marginStart = p; marginEnd = p })
            addView(item("▦", "Show QR code", "For people right next to you") { showConvoyQr() })
            addView(View(context).apply { setBackgroundColor(dim(50)) }, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, d.toInt())).apply { marginStart = p; marginEnd = p })
            addView(item("🔗", "Share the link", "Send it in a message") { inviteToConvoy() })
        }
        L.translateTree(box); Icons.applyTree(box)
        pop = android.widget.PopupWindow(box, (290 * d).toInt(), WRAP_CONTENT, true).apply { elevation = 16 * d; isOutsideTouchable = true }
        pop.showAsDropDown(anchor, 0, -(anchor.height + 140 * d).toInt())
    }

    /**
     * The convoy's QR, big: scanned with any camera it opens GameRoute and joins (no app: the convoy
     * opens in the browser). The screen goes to full brightness while it shows (dim screens scan badly).
     */
    internal fun showConvoyQr() {
        val link = Convoy.link() ?: return
        val p = (20 * d).toInt()
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(p + p / 4, p, p + p / 4, p + p / 4); setBackgroundColor(panel)
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label("Join the convoy", 24f, bold = true).apply { setTextColor(ink); typeface = theme.typeface }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("✕") { dlg.dismiss() }.apply { setTextColor(ink); contentDescription = L.t("Close") })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        box.addView(label("Scan with the phone's camera: it opens GameRoute and joins. No app? The convoy opens in the browser.", 15f).apply {
            setTextColor(dim()); setPadding(0, p / 3, 0, p)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val side = minOf(resources.displayMetrics.widthPixels - 3 * p, (320 * d).toInt())
        box.addView(android.widget.ImageView(this).apply {
            setImageBitmap(qr(link, side + (32 * d).toInt())); contentDescription = L.t("QR code for the convoy ${Convoy.pretty()}")
            background = bg(Color.WHITE, radius = 16f); scaleType = android.widget.ImageView.ScaleType.CENTER
        }, LinearLayout.LayoutParams(side + (32 * d).toInt(), side + (32 * d).toInt()))
        box.addView(label("CODE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.12f; setPadding(0, p, 0, 0) })
        box.addView(label(Convoy.pretty() ?: "", 40f, bold = true).apply { setTextColor(ink); letterSpacing = 0.16f; typeface = Typeface.MONOSPACE })
        Convoy.destination?.let { box.addView(label("🏁  ${it.name}", 15f, bold = true).apply { setTextColor(ink); setPadding(0, p / 3, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }) }
        box.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Share the link") { dlg.dismiss(); inviteToConvoy() }.apply { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 16f) },
                LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f).apply { marginEnd = p / 2 })
            addView(pill("Done") { dlg.dismiss() }.apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 16f) },
                LinearLayout.LayoutParams(0, (52 * d).toInt(), 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        L.translateTree(box); Icons.applyTree(box)
        dlg.setContentView(box)
        dlg.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f } } // only while it shows
        dlg.show()
    }

    /** In the convoy list: add someone as a friend (or that you are, or asked already). */
    private fun friendMark(memberId: String): View? {
        if (!Friends.ready) return null
        val m = Convoy.members[memberId] ?: return null
        if (m.fc.isBlank() || m.fid.isBlank()) return null
        val c = Friends.core
        val sz = (40 * d).toInt()
        return when {
            c.isFriend(m.fid) -> label("✓", 17f, bold = true).apply { setTextColor(readable(Color.rgb(47, 180, 100))); gravity = Gravity.CENTER; contentDescription = L.t("Friends") }
            c.requested(m.fc) -> label("⏱", 16f).apply { setTextColor(dim()); gravity = Gravity.CENTER; contentDescription = L.t("Request sent") }
            m.fid in c.blocked -> null
            else -> label("＋", 20f, bold = true).apply {
                setTextColor(readable(accent)); gravity = Gravity.CENTER; background = bg(Color.TRANSPARENT, accent, 1.5f, 10f)
                contentDescription = L.t("Add ${m.name} as a friend")
                setOnClickListener { sendFriendRequest(m.fc, m.name) { convoyKey = ""; refreshChrome() } }
            }
        }?.apply { layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginStart = (8 * d).toInt() } }
    }

    private var blockedDialog: GameDialog? = null
    private fun showBlocked() {
        val list = Friends.core.blockedNames()
        if (list.isEmpty()) { Toast.makeText(this, L.t("Nobody is blocked"), Toast.LENGTH_SHORT).show(); return }
        GameBuilder(this@MainActivity).setTitle("Blocked")
            .setItems(list.map { L.t("Unblock ${it.second}") }.toTypedArray()) { _, w -> Friends.unblock(list[w].first); showSettings() }
            .setNegativeButton("Close", null).showT()
    }

    private var offlineKey = ""
    private val offlineListener: () -> Unit = { runOnUiThread { if (sheetKind == "offline") { offlineKey = ""; renderOffline() } } }

    /** Offline maps: download the map around here, list and delete downloads. */
    private fun renderOffline() {
        val areas = Offline.areas()
        val key = "${areas.size}|${Offline.downloading}|${Offline.done.get() / 20}|${Offline.waitingForWifi}|${Offline.wifiOnly}${Offline.autoUpdate}${Offline.alongRoutes}|${OfflineRouter.present}${OfflineRouter.downloading}${(OfflineRouter.progress * 50).toInt()}${Offline.roadsSize}|${theme.name}|${MapState.dark}"
        if (key == offlineKey && sheetHost.childCount > 0) return
        offlineKey = key
        Offline.changed -= offlineListener; Offline.changed += offlineListener
        // (rebuilt as a download goes on: keep where the list was scrolled to)
        val keepScroll = (0 until sheetHost.childCount).map { sheetHost.getChildAt(it) }.filterIsInstance<ScrollView>().firstOrNull()?.scrollY ?: 0
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("Offline maps"))
        val p = (12 * d).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.78).apply { addView(body); post { scrollTo(0, keepScroll) } }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        body.addView(label("The map and the places of a region, kept on your phone: they show and search without signal and use no data. " +
            "Every trip also keeps the map along its route.", 14f).apply { setTextColor(dim()); setPadding(0, p / 2, 0, p / 2) })
        body.addView(label("${Offline.size(Offline.usedBytes())} used · ${Offline.size(Offline.freeBytes())} free on the phone", 13f, bold = true).apply {
            setTextColor(dim()); setPadding(0, 0, 0, p / 2)
        })
        // settings
        body.addView(switchRow("Download on Wi-Fi only", Offline.wifiOnly) { Offline.wifiOnly = it })
        body.addView(switchRow("Update downloaded maps automatically", Offline.autoUpdate) { Offline.autoUpdate = it })
        body.addView(switchRow("Keep the map along every route", Offline.alongRoutes) { Offline.alongRoutes = it })
        // what's downloading
        val dl = Offline.downloading
        if (dl != null) {
            val shown = Offline.regions.firstOrNull { it.id == dl }?.name ?: dl
            body.addView(label(if (Offline.waitingForWifi) "Waiting for Wi-Fi: $shown" else "Downloading $shown… ${Offline.done.get() * 100 / Offline.total.coerceAtLeast(1)}%", 15f, bold = true).apply {
                setTextColor(readable(accent)); setPadding(0, p, 0, 0)
            })
            body.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = Offline.total.coerceAtLeast(1); progress = Offline.done.get()
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2; bottomMargin = p / 2 })
            body.addView(pill("Stop") { Offline.cancelled = true }.apply { setTextColor(ink) })
        }
        val date = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
        // offline routing: the road file for Greece
        Offline.loadRoadsInfo()
        body.addView(label("OFFLINE ROUTING", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(if (OfflineRouter.present) 30 else 16), radius = 14f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label((if (OfflineRouter.present) "✓ " else "") + L.t("Roads of Greece"), 15.5f, bold = true).apply { setTextColor(ink) })
                val sub = when {
                    OfflineRouter.downloading -> L.t("Downloading… ${(OfflineRouter.progress * 100).toInt()}%")
                    OfflineRouter.present -> "${Offline.size(OfflineRouter.bytes())} · ${date.format(java.util.Date(OfflineRouter.date()))} · " + L.t("routes and reroutes with no signal")
                    Offline.roadsSize > 0 -> L.t("New routes and reroutes with no signal") + " · " + L.t("about ${Offline.size(Offline.roadsSize)}")
                    else -> L.t("New routes and reroutes with no signal")
                }
                addView(label(sub, 13f).apply { setTextColor(dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            when {
                OfflineRouter.downloading -> {}
                OfflineRouter.present -> addView(pill("Delete") {
                    GameBuilder(this@MainActivity).setMessage(L.t("Delete the offline roads? Routes will need the internet again."))
                        .setPositiveButton("Delete") { _, _ -> OfflineRouter.delete(); Offline.changed.fire() }.setNegativeButton("Cancel", null).showT()
                }.apply { textSize = 14f; setTextColor(readable(Color.rgb(220, 60, 60))) })
                else -> addView(pill("⬇ Download") {
                    if (Offline.wifiOnly && !Offline.onWifi()) Toast.makeText(this@MainActivity, L.t("It starts as soon as you're on Wi-Fi"), Toast.LENGTH_LONG).show()
                    Offline.downloadRoads()
                }.apply { textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) })
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
        // regions
        body.addView(label("REGIONS", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
        for (r in Offline.regions) {
            val have = Offline.downloaded(r)
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(if (have != null) 30 else 16), radius = 14f)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label((if (have != null) "✓ " else "") + r.name, 15.5f, bold = true).apply { setTextColor(ink) })
                    addView(label(if (have != null) "${Offline.size(have.bytes)} · ${date.format(java.util.Date(have.at))}" + (if (Offline.autoUpdate) " · updates itself" else "")
                        else "about ${Offline.size(Offline.estimate(Offline.tileCount(r)))}", 13f).apply { setTextColor(dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                if (have != null) addView(pill("Delete") {
                    GameBuilder(this@MainActivity).setMessage(L.t("Delete the offline map of ${r.name}?"))
                        .setPositiveButton("Delete") { _, _ -> Offline.delete(have) }.setNegativeButton("Cancel", null).showT()
                }.apply { textSize = 14f; setTextColor(readable(Color.rgb(220, 60, 60))) })
                else if (dl == null) addView(pill("⬇ Download") {
                    if (Offline.wifiOnly && !Offline.onWifi()) Toast.makeText(this@MainActivity, L.t("It starts as soon as you're on Wi-Fi"), Toast.LENGTH_LONG).show()
                    Offline.download(r)
                }.apply { textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
        }
        // around the map's centre (a smaller piece)
        if (dl == null) {
            val center = map.center() ?: LocationTracker.position
            if (center != null) {
                body.addView(label("AROUND THE MAP'S CENTRE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
                for ((km, title) in listOf(5 to "City centre", 15 to "Wider area")) {
                    val n = Offline.tileCount(center, km)
                    body.addView(pill("⬇  $title · ${km} km · about ${Offline.size(Offline.estimate(n) * 3)}") {
                        io.execute {
                            val name = try { Places.addressAt(center).let { a -> a.address.split(", ").firstOrNull()?.takeIf { it.isNotBlank() } ?: a.name } } catch (e: Exception) { null }
                                ?: "Area ${"%.2f".format(center.lat)}, ${"%.2f".format(center.lon)}"
                            Offline.download("$name ($km km)", center, km)
                        }
                    }.apply {
                        textSize = 14f; setTextColor(ink); background = bg(Color.TRANSPARENT, accent, 1.5f, 14f)
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
                }
            }
        }
        // areas downloaded by distance (not regions)
        val others = areas.filter { a -> Offline.regions.none { it.id == a.name } }
        if (others.isNotEmpty()) body.addView(label("ON THIS PHONE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
        for (a in others) {
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(22), radius = 14f)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(a.name, 16f, bold = true).apply { setTextColor(ink) })
                    addView(label("${Offline.size(a.bytes)} · downloaded ${date.format(java.util.Date(a.at))}" + (if (Offline.autoUpdate) " · updates itself" else ""), 13f).apply { setTextColor(dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(pill("Delete") {
                    GameBuilder(this@MainActivity).setMessage("Delete the offline map of ${a.name}?")
                        .setPositiveButton("Delete") { _, _ -> Offline.delete(a) }.setNegativeButton("Cancel", null).showT()
                }.apply { textSize = 14f; setTextColor(readable(Color.rgb(220, 60, 60))) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
        }
    }

    private var tripsKey = ""

    /** Past trips, newest first, with totals at the top. */
    private var recapYear = false
    private val recapShown = HashSet(Recap.LINES.map { it.first })
    private var recapKey = ""

    /** Your recap: this month or this year as a card; choose the lines; Share. */
    private fun renderRecap() {
        val key = "$recapYear|${recapShown.sorted()}|${theme.name}|${MapState.dark}|${Explorer.history().size}"
        if (key == recapKey && sheetHost.childCount > 0) return
        recapKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("Your recap"))
        val p = (12 * d).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.78).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // month / year
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p / 2, 0, p / 2)
            for ((yr, t) in listOf(false to "This month", true to "This year")) addView(pill(L.t(t)) { recapYear = yr; recapKey = ""; refreshChrome() }.apply {
                if (recapYear == yr) { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) } else { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) }
            }, LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f).apply { marginEnd = p / 2 })
        })
        val stats = Recap.stats(recapYear)
        val title = Xp.title()
        // the card itself
        body.addView(object : View(this) {
            override fun onDraw(c: Canvas) { Recap.draw(c, width.toFloat(), height.toFloat(), stats, recapShown, theme, Convoy.myName, title) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { height = ((resources.displayMetrics.widthPixels - 2 * p) * 1.25f).toInt().coerceAtMost((440 * d).toInt()) })
        // what's on it
        body.addView(sectionTitle(L.t("ON THE CARD")))
        for ((k, label) in Recap.LINES) body.addView(switchRow(label, k in recapShown) { on -> if (on) recapShown += k else recapShown -= k; recapKey = ""; refreshChrome() })
        body.addView(label(L.t("Never on it: Home, Work, or where and when you drove."), 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p / 3, 0, p / 2) })
        sheetHost.addView(pill("⤴  " + L.t("Share")) { shareRecap(stats, title) }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 14f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, (50 * d).toInt()).apply { topMargin = p / 2 })
    }

    /** The card as a picture (1080 × 1350), shared to the app you pick. */
    private fun shareRecap(stats: Recap.Stats, title: String) {
        try {
            val bmp = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
            Recap.draw(Canvas(bmp), 1080f, 1350f, stats, recapShown, theme, Convoy.myName, title)
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val f = java.io.File(dir, "recap.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.share", f)
            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, L.t("My ${stats.period} on the road with GameRoute")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, L.t("Share your recap")))
        } catch (e: Exception) { Toast.makeText(this, L.t("Couldn't share the card"), Toast.LENGTH_SHORT).show() }
    }

    private fun renderTrips() {
        val list = Explorer.history()
        val key = "${list.size}|${list.firstOrNull()?.arrivedAt}|${theme.name}|${MapState.dark}"
        if (key == tripsKey && sheetHost.childCount > 0) return
        tripsKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("Trip history"))
        val p = (12 * d).toInt()
        if (list.isNotEmpty()) sheetHost.addView(pill("📅  " + L.t("Your recap")) { openSheet("recap") }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 14f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, (46 * d).toInt()).apply { topMargin = p / 2 })
        val km = list.sumOf { it.meters } / 1000
        val hours = list.sumOf { it.seconds } / 3600.0
        val saved = list.sumOf { it.savedSeconds } / 60
        sheetHost.addView(label(
            if (list.isEmpty()) "Your finished trips will show here."
            else "${list.size} trips · ${NavEngine.formatDistance(km * 1000)} · ${"%.1f".format(hours)} h driving" +
                (if (saved > 0) " · $saved min saved dodging traffic" else ""), 15f, bold = true,
        ).apply { setTextColor(readable(accent)); setPadding(0, p / 2, 0, p) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val day = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault())
        var lastDay = ""
        for (t in list) {
            val dLabel = day.format(java.util.Date(t.arrivedAt))
            if (dLabel != lastDay) {
                lastDay = dLabel
                body.addView(label(dLabel.uppercase(), 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(p, p * 3 / 4, p, p * 3 / 4)
                background = bg(dim(22), radius = 14f)
            }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(t.place, 16f, bold = true).apply { setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(label("${NavEngine.clock(t.arrivedAt - t.seconds * 1000L)} → ${NavEngine.clock(t.arrivedAt)} · " +
                    "${NavEngine.formatDuration(t.seconds.toDouble())} · ${NavEngine.formatDistance(t.meters)}", 13f).apply { setTextColor(dim()) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(label("+${t.xp} XP", 14f, bold = true).apply { setTextColor(readable(accent)) })
            body.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
        }
        if (list.isNotEmpty()) body.addView(pill("Clear history") {
            GameBuilder(this@MainActivity).setMessage("Delete all ${list.size} trips from the history? Your XP stays.")
                .setPositiveButton("Delete") { _, _ -> Explorer.clearHistory(); tripsKey = ""; refreshChrome() }
                .setNegativeButton("Cancel", null).showT()
        }.apply { setTextColor(dim()) })
        sheetHost.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(MATCH_PARENT, minOf((resources.displayMetrics.heightPixels * 0.62f).toInt(), (560 * d).toInt())))
    }

    private var progressKey = ""

    /** Your progress: level, title, streak, this week's challenges, the title ladder, prestige. */
    private fun renderProgress() {
        val key = "${Explorer.xp}|${Xp.prestige}|${theme.name}|${MapState.dark}|${Xp.streak}|${Xp.challenges().joinToString { "${it.have}" }}|${Xp.showToFriends}"
        if (key == progressKey && sheetHost.childCount > 0) return
        progressKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("Your progress"))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.74).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val p = (12 * d).toInt()
        val (have, of) = Explorer.progress()
        val lvl = Explorer.level
        // level, title and the bar
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, 0)
            addView(label("${lvl}", 30f, bold = true).apply {
                setTextColor(onAccent(accent)); gravity = Gravity.CENTER; typeface = theme.typeface; background = bg(accent, radius = 16f)
            }, LinearLayout.LayoutParams((64 * d).toInt(), (64 * d).toInt()).apply { marginEnd = p })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(Convoy.myName.ifBlank { L.t("You") }, 20f, bold = true).apply { setTextColor(ink); typeface = theme.typeface; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(label(Xp.stars + L.t(Xp.title()) + " · " + L.t(theme.title), 14f, bold = true).apply { setTextColor(readable(accent)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.rankMark())
                addView(label(L.t("Level $lvl · ${Explorer.xp} XP") + (if (Xp.prestige > 0) " · " + L.t("prestige ${Xp.prestige}") else ""), 13.5f).apply { setTextColor(dim()) })
                addView(bar(have.toFloat() / maxOf(of, 1), accent), LinearLayout.LayoutParams(MATCH_PARENT, (9 * d).toInt()).apply { topMargin = p / 2 })
                val next = Xp.nextUnlock(lvl)
                addView(label(L.t("${of - have} XP to level ${lvl + 1}") + (next?.takeIf { it.level == lvl + 1 }?.let { " · " + L.t("unlocks ${it.name}") } ?: ""), 12.5f).apply { setTextColor(dim()); setPadding(0, p / 4, 0, 0) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        // streak and the Garage
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
            fun box(top: String, big: String, sub: String, col: Int, onClick: (() -> Unit)?) = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(p, p * 2 / 3, p, p * 2 / 3)
                background = bg(Color.argb(30, Color.red(col), Color.green(col), Color.blue(col)), col, 1.5f, 14f)
                addView(label(top, 11.5f, bold = true).apply { setTextColor(readable(col)); letterSpacing = 0.1f })
                addView(label(big, 19f, bold = true).apply { setTextColor(ink) })
                addView(label(sub, 12.5f).apply { setTextColor(dim()) })
                if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
            }
            val st = Xp.streak
            addView(box(L.t("STREAK"), if (st > 0) "🔥 " + L.t(if (st == 1) "1 day" else "$st days") else L.t("No streak"), L.t("+10 XP a day, up to +50"), Color.rgb(255, 176, 40), null),
                LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = p * 2 / 3 })
            addView(box(L.t("GARAGE"), L.t("${Xp.items.count { it.unlocked }} of ${Xp.items.size}"), L.t("Arrows, route colours, flags") + " ›", accent) { openSheet("garage") },
                LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        })
        // this week
        body.addView(sectionTitle(L.t("THIS WEEK · NEW EVERY MONDAY")))
        for (c in Xp.challenges()) body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p * 2 / 3, p, p * 2 / 3); background = bg(dim(20), radius = 12f)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(L.t(c.title), 15.5f, bold = true).apply { setTextColor(ink) })
                addView(bar(c.have.toFloat() / c.of, if (c.done) Color.rgb(47, 180, 100) else Color.rgb(255, 176, 40)), LinearLayout.LayoutParams(MATCH_PARENT, (6 * d).toInt()).apply { topMargin = p / 3 })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p })
            addView(label(if (c.done) "✓ ${c.reward} XP" else "${c.have}/${c.of} · ${c.reward} XP", 14f, bold = true).apply {
                setTextColor(readable(if (c.done) Color.rgb(47, 180, 100) else Color.rgb(255, 176, 40)))
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
        // the titles of this style
        body.addView(sectionTitle(L.t("TITLES IN ${L.t(theme.title).uppercase()}")))
        Xp.ladder(theme).forEachIndexed { i, t ->
            val at = Xp.titleLevels[i]
            val now = Xp.title() == t && (i + 1 >= Xp.titleLevels.size || lvl < Xp.titleLevels[i + 1])
            val got = lvl >= at
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 2, p / 3, 0, p / 3)
                addView(View(context).apply { background = if (got) bg(if (now) accent else dim(160), radius = 7f) else bg(Color.TRANSPARENT, dim(120), 2f, 7f) },
                    LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt()).apply { marginEnd = p })
                addView(label("Lv $at", 13f, bold = true).apply { setTextColor(if (now) readable(accent) else dim()); minWidth = (52 * d).toInt() })
                addView(label(L.t(t), 16f, bold = now).apply { setTextColor(if (got) ink else dim()) })
            })
        }
        // prestige: the ranks, what the next one brings, and the button at level 50
        body.addView(sectionTitle(L.t("PRESTIGE")))
        val (nextName, nextBoost, nextItems) = Xp.nextRank()
        if (Xp.prestige > 0) body.addView(label("◆ " + L.t(Xp.rankName()) + " · +${Xp.boostPct}% XP", 16f, bold = true).apply {
            setTextColor(readable(Xp.rankColor())); setPadding(p / 4, 0, 0, p / 3)
        })
        body.addView(label(if (Xp.canPrestige) L.t("You reached level 50. Prestige to start again at level 1 as $nextName: +$nextBoost% XP for good and new Garage rewards. Everything you unlocked stays.")
            else L.t("At level 50 you can prestige: start again at level 1 as $nextName, with +$nextBoost% XP for good and new Garage rewards. Everything you unlocked stays."), 13.5f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p / 2) })
        // the five ranks in a row: done, next, later
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, p / 2)
            Xp.ranks.forEachIndexed { i, rk ->
                val got = Xp.prestige >= i + 1
                val col = Xp.rankColors[i]
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(label("◆", 22f, bold = true).apply { setTextColor(if (got) col else dim(90)); gravity = Gravity.CENTER })
                    addView(label(L.t(rk), 11.5f, bold = got).apply { setTextColor(if (got) ink else dim()); gravity = Gravity.CENTER })
                    addView(label("+${(i + 1) * 10}%", 11f).apply { setTextColor(dim()); gravity = Gravity.CENTER })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (nextItems.isNotEmpty()) body.addView(label(L.t("$nextName rewards: ") + nextItems.joinToString(", ") { L.t(it.name) }, 13f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p / 2) })
        if (Xp.canPrestige) body.addView(pill("◆  " + L.t("Prestige to $nextName")) {
            GameBuilder(this@MainActivity).setTitle(L.t("Become $nextName?")).setMessage(L.t("You start again at level 1 with +$nextBoost% XP for good. Everything in the Garage stays, and the $nextName rewards are added."))
                .setPositiveButton(L.t("Prestige")) { _, _ -> Xp.doPrestige(); progressKey = ""; refreshChrome(); showPrestigeReveal() }.setNegativeButton("Cancel", null).showT()
        }.apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) }, LinearLayout.LayoutParams(MATCH_PARENT, (50 * d).toInt()))
        // friends
        body.addView(switchRow("Show my level to friends", Xp.showToFriends) { Xp.showToFriends = it; Friends.Presence.refresh() })
        body.addView(label("Off: only you see it. On: your level and title show next to your name for friends and in convoys (never your trips or where you've been).", 12.5f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p) })
    }

    /** A thin progress bar. */
    private fun bar(f: Float, col: Int) = object : View(this) {
        private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = height / 2f
            pt.color = dim(50); c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, pt)
            pt.color = col; c.drawRoundRect(0f, 0f, width * f.coerceIn(0f, 1f), height.toFloat(), r, r, pt)
        }
    }

    private var garageKey = ""
    private var garageTab = "arrow"

    /** The Garage: pick your arrow, route colour, destination flag and arrival effect. */
    private fun renderGarage() {
        val key = "${Xp.version}|${Xp.maxLevel}|$garageTab|${theme.name}|${MapState.dark}"
        if (key == garageKey && sheetHost.childCount > 0) return
        garageKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("Garage"))
        val p = (12 * d).toInt()
        sheetHost.addView(label(Xp.stars + L.t(Xp.title()) + " · " + L.t("level ${Explorer.level}") + " · " + L.t(theme.title), 13.5f).apply { setTextColor(dim()); setPadding(p / 4, 0, 0, p / 2) }.rankMark())
        // tabs
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val q = (4 * d).toInt(); setPadding(q, q, q, q); background = bg(dim(26), radius = 14f)
            for ((k, t) in listOf("arrow" to "Arrow", "route" to "Route", "flag" to "Flag", "arrival" to "Arrival")) {
                val sel = k == garageTab
                addView(TextView(context).apply {
                    text = L.t(t); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                    setTextColor(if (sel) onAccent(accent) else ink); if (sel) background = bg(accent, radius = 11f)
                    setOnClickListener { garageTab = k; renderGarage() }
                }, LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f))
            }
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheetHost.addView(SheetScroll(0.6).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
        val look = theme.look
        val list = Xp.kindItems(garageTab)
        val chosen = Xp.chosen(garageTab)
        var row: LinearLayout? = null
        list.forEachIndexed { i, it ->
            val item = it
            if (i % 2 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; body.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 }) }
            val readable = Xp.readableOn(it, look)
            val usable = it.unlocked && readable
            val sel = it.id == chosen.id
            row?.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p)
                background = bg(dim(20), if (sel) accent else dim(60), if (sel) 2.5f else 1f, 14f)
                alpha = if (usable) 1f else 0.5f
                addView(GarageSwatch(context, it, look), LinearLayout.LayoutParams(MATCH_PARENT, (64 * d).toInt()))
                addView(label(L.t(it.name), 15f, bold = true).apply { setTextColor(ink); setPadding(0, p / 2, 0, 0) })
                addView(label(when {
                    sel -> L.t("In use")
                    !it.unlocked && it.rank > 0 -> "◆ " + L.t(Xp.rankName(it.rank)) + " " + L.t("rank")
                    !it.unlocked && it.level >= 50 -> L.t("Prestige")
                    !it.unlocked -> "🔒 " + L.t("Level ${it.level}") + if (it.milestone) " · " + L.t("milestone") else ""
                    !readable -> L.t("Hard to see on this style")
                    else -> L.t("Tap to use")
                }, 12.5f, bold = sel).apply { setTextColor(if (sel) readable(accent) else dim()) })
                isClickable = usable
                setOnClickListener { if (usable) { Xp.choose(item); MapState.changed.fire(); renderGarage() } }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i % 2 == 0) marginEnd = p / 2 })
        }
        if (list.size % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        body.addView(label(when (garageTab) {
            "route" -> "Only colours that stand out on the current style can be used, so the route is always easy to follow."
            "arrival" -> "Plays on the arrival card, once you've stopped. Never while driving."
            "flag" -> "Marks where you're going on the map."
            else -> "Your arrow on the map. Every shape points the way you drive."
        }, 12.5f).apply { setTextColor(dim()); setPadding(p / 4, p, 0, p / 2) })
    }

    /** A small drawing of a Garage item. */
    private inner class GarageSwatch(ctx: android.content.Context, val item: Xp.Item, val look: Look) : View(ctx) {
        private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2
            pt.style = android.graphics.Paint.Style.FILL; pt.color = look.style.land
            c.drawRoundRect(0f, 0f, w, h, 10 * d, 10 * d, pt)
            val s = h * 0.32f
            when (item.kind) {
                "arrow" -> {
                    val pa = android.graphics.Path()
                    when (item.id) {
                        "arrow_chevron" -> { pa.moveTo(cx, cy - s); pa.lineTo(cx + s * 0.85f, cy + s * 0.55f); pa.lineTo(cx + s * 0.45f, cy + s * 0.78f); pa.lineTo(cx, cy + s * 0.08f); pa.lineTo(cx - s * 0.45f, cy + s * 0.78f); pa.lineTo(cx - s * 0.85f, cy + s * 0.55f) }
                        "arrow_dart" -> { pa.moveTo(cx, cy - s * 1.15f); pa.lineTo(cx + s * 0.5f, cy + s * 0.85f); pa.lineTo(cx, cy + s * 0.5f); pa.lineTo(cx - s * 0.5f, cy + s * 0.85f) }
                        "arrow_delta" -> { pa.moveTo(cx, cy - s); pa.lineTo(cx + s * 0.95f, cy + s * 0.85f); pa.lineTo(cx - s * 0.95f, cy + s * 0.85f) }
                        "arrow_comet" -> { pa.moveTo(cx, cy - s * 1.05f); pa.lineTo(cx + s * 0.8f, cy - s * 0.35f); pa.lineTo(cx + s * 0.6f, cy + s * 0.55f); pa.lineTo(cx, cy + s * 0.95f); pa.lineTo(cx - s * 0.6f, cy + s * 0.55f); pa.lineTo(cx - s * 0.8f, cy - s * 0.35f) }
                        else -> { pa.moveTo(cx, cy - s); pa.lineTo(cx + s * 0.75f, cy + s * 0.8f); pa.lineTo(cx, cy + s * 0.4f); pa.lineTo(cx - s * 0.75f, cy + s * 0.8f) }
                    }
                    pa.close()
                    pt.color = if (item.color != 0) item.color else look.player; c.drawPath(pa, pt)
                    pt.style = android.graphics.Paint.Style.STROKE; pt.strokeWidth = 2.2f * d
                    pt.color = if (item.color != 0) Color.rgb(40, 28, 0) else look.playerOutline; c.drawPath(pa, pt)
                }
                "route" -> {
                    val col = if (item.color != 0) item.color else look.route
                    pt.style = android.graphics.Paint.Style.STROKE; pt.strokeCap = android.graphics.Paint.Cap.ROUND
                    pt.color = Color.argb(90, Color.red(col), Color.green(col), Color.blue(col)); pt.strokeWidth = 16 * d
                    c.drawLine(w * 0.15f, cy + s * 0.6f, w * 0.85f, cy - s * 0.6f, pt)
                    pt.color = col; pt.strokeWidth = 7 * d
                    c.drawLine(w * 0.15f, cy + s * 0.6f, w * 0.85f, cy - s * 0.6f, pt)
                }
                "flag" -> {
                    pt.style = android.graphics.Paint.Style.FILL
                    when (item.id) {
                        "flag_checkered" -> { val q = s * 0.32f; val l = cx; val t = cy - s
                            for (i in 0 until 4) for (j in 0 until 3) { pt.color = if ((i + j) % 2 == 0) Color.WHITE else Color.BLACK; c.drawRect(l + i * q, t + j * q, l + (i + 1) * q, t + (j + 1) * q, pt) }
                            pt.color = Color.BLACK; c.drawRect(cx - 1.5f * d, cy - s, cx + 1.5f * d, cy + s, pt) }
                        "flag_pennant" -> { val pa = android.graphics.Path().apply { moveTo(cx, cy - s); lineTo(cx + s * 1.3f, cy - s * 0.6f); lineTo(cx, cy - s * 0.15f); close() }
                            pt.color = look.route; c.drawPath(pa, pt); pt.color = Color.BLACK; c.drawRect(cx - 1.5f * d, cy - s, cx + 1.5f * d, cy + s, pt) }
                        "flag_trophy" -> { pt.color = Color.rgb(255, 196, 40)
                            val pa = android.graphics.Path().apply { moveTo(cx - s * 0.7f, cy - s); lineTo(cx + s * 0.7f, cy - s); lineTo(cx + s * 0.45f, cy); quadTo(cx, cy + s * 0.3f, cx - s * 0.45f, cy); close() }
                            c.drawPath(pa, pt); c.drawRect(cx - s * 0.12f, cy + s * 0.1f, cx + s * 0.12f, cy + s * 0.55f, pt); c.drawRect(cx - s * 0.5f, cy + s * 0.55f, cx + s * 0.5f, cy + s * 0.8f, pt) }
                        else -> { pt.color = Color.rgb(255, 214, 0); c.drawCircle(cx, cy, s * 0.55f, pt)
                            pt.style = android.graphics.Paint.Style.STROKE; pt.strokeWidth = 2 * d; pt.color = Color.BLACK; c.drawCircle(cx, cy, s * 0.55f, pt) }
                    }
                }
                else -> { // arrival: a few dots in its colours
                    val cols = when (item.id) {
                        "arrival_confetti" -> intArrayOf(Color.rgb(255, 80, 120), Color.rgb(80, 200, 255), Color.rgb(255, 210, 60), Color.rgb(120, 230, 120))
                        "arrival_fireworks" -> intArrayOf(Color.rgb(255, 160, 40), Color.rgb(255, 80, 200), Color.rgb(255, 240, 160))
                        "arrival_lights" -> intArrayOf(look.route, Color.WHITE, look.player)
                        else -> intArrayOf(look.route)
                    }
                    pt.style = android.graphics.Paint.Style.FILL
                    val r = java.util.Random(item.id.hashCode().toLong())
                    repeat(if (item.id == "arrival_classic") 1 else 14) { k ->
                        pt.color = cols[k % cols.size]
                        val x = if (item.id == "arrival_classic") cx else w * (0.1f + r.nextFloat() * 0.8f)
                        val y = if (item.id == "arrival_classic") cy else h * (0.15f + r.nextFloat() * 0.7f)
                        c.drawCircle(x, y, (if (item.id == "arrival_classic") 8f else 2.5f + r.nextFloat() * 3f) * d, pt)
                    }
                }
            }
        }
    }

    /** After a level-up (shown once you've parked): the new level, title and unlocks. */
    /**
     * Level up (after parking). Crossing a multiple of 5: first the celebration in this style's
     * look (Settings › Performance › Animations: Full only), then the card.
     */
    /** The highest level already celebrated (remembered, so a level-up is never missed nor shown twice). */
    private fun celebratedLevel(): Int {
        val p = prefs()
        // (a new name: a test build marked a celebration as seen before it had played to the end)
        if (!p.contains("levelCelebrated")) p.edit().putInt("levelCelebrated", (Explorer.level - 1).coerceAtLeast(1)).apply()
        return p.getInt("levelCelebrated", Explorer.level)
    }

    private fun showLevelUp(from: Int) {
        val to = Explorer.level
        if (to <= from || isFinishing) return
        if (to <= celebratedLevel() || celebrating) return
        celebrating = true
        val milestone = (from + 1..to).lastOrNull { it % 5 == 0 }
        if (milestone != null && Perf.anim == Perf.FULL) {
            val host = window.decorView as ViewGroup
            lateinit var view: Milestone
            view = Milestone(this, milestone, Xp.title(theme, milestone), theme) {
                host.post { (view.parent as? ViewGroup)?.removeView(view); levelSeen(milestone.coerceAtLeast(to)); showLevelUpCard(from) }
            }
            view.elevation = 60 * d
            host.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            return
        }
        levelSeen(to)
        showLevelUpCard(from)
    }

    /** Only now counted as celebrated: once it has really been shown (not when it starts). */
    private var celebrating = false
    private fun levelSeen(level: Int) {
        celebrating = false
        prefs().edit().putInt("levelCelebrated", maxOf(level, prefs().getInt("levelCelebrated", 0))).apply()
    }

    /** Developer page: play the celebration for [level] now (nothing is counted). */
    private fun playCelebration(level: Int) {
        val host = window.decorView as ViewGroup
        lateinit var view: Milestone
        // then the card for that same level, with what it unlocks
        view = Milestone(this, level, Xp.title(theme, level), theme) { host.post { (view.parent as? ViewGroup)?.removeView(view); showLevelUpCard(level - 1, level, preview = true) } }
        view.elevation = 60 * d
        host.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /** For tests: the celebration for [level], drawn at [ms]. */
    internal fun milestoneForTest(level: Int, ms: Long): Milestone = Milestone(this, level, Xp.title(theme, level), theme) {}.apply { fixedMs = ms }

    /** [preview]: the Developer page showing the card for [toLevel] (nothing is chosen or unlocked). */
    private fun showLevelUpCard(from: Int, toLevel: Int = Explorer.level, preview: Boolean = false) {
        val to = toLevel
        if (to <= from || isFinishing) return
        val p = (20 * d).toInt()
        lateinit var dlg: AlertDialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(p, p, p, p)
            addView(label(L.t("LEVEL UP"), 14f, bold = true).apply { setTextColor(readable(accent)); letterSpacing = 0.3f })
            addView(label("$to", 64f, bold = true).apply { setTextColor(ink); typeface = theme.typeface; gravity = Gravity.CENTER })
            val oldT = Xp.title(theme, from); val newT = Xp.title(theme, to)
            if (oldT != newT) {
                addView(label(L.t("New title"), 13f).apply { setTextColor(dim()) })
                addView(label(Xp.stars + L.t(newT), 22f, bold = true).apply { setTextColor(ink); typeface = theme.typeface }.rankMark())
                addView(label(L.t(oldT) + " → " + L.t(newT), 13f).apply { setTextColor(dim()) })
            } else addView(label(Xp.stars + L.t(newT), 18f, bold = true).apply { setTextColor(ink) }.rankMark())
            val unlocks = (from + 1..to).flatMap { Xp.unlockedAt(it) }
            if (unlocks.isNotEmpty()) {
                addView(label(L.t("UNLOCKED"), 12f, bold = true).apply { setTextColor(readable(accent)); letterSpacing = 0.12f; setPadding(0, p, 0, p / 3) })
                for (u in unlocks) addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(GarageSwatch(context, u, theme.look), LinearLayout.LayoutParams((70 * d).toInt(), (48 * d).toInt()).apply { marginEnd = p / 2 })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(L.t(u.name), 16f, bold = true).apply { setTextColor(ink) })
                        addView(label(L.t(when (u.kind) { "arrow" -> "New arrow"; "route" -> "New route colour"; "flag" -> "New destination flag"; else -> "New arrival effect" }), 13f).apply { setTextColor(dim()) })
                    })
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 3 })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
                addView(pill("Later") { dlg.dismiss() }.apply { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) },
                    LinearLayout.LayoutParams(0, (50 * d).toInt(), 1f).apply { marginEnd = p / 2 })
                if (preview) addView(label(L.t("Preview (test build)"), 13f).apply { setTextColor(dim()); gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(0, (50 * d).toInt(), 1.3f))
                else if (to >= 50) addView(pill("See prestige") { dlg.dismiss(); openSheet("progress") }
                    .apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) }, LinearLayout.LayoutParams(0, (50 * d).toInt(), 1.3f))
                else if (unlocks.isNotEmpty()) addView(pill("Use it now") {
                    dlg.dismiss(); val u = unlocks.last(); if (Xp.readableOn(u, theme.look)) Xp.choose(u); garageTab = u.kind; openSheet("garage")
                }.apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) }, LinearLayout.LayoutParams(0, (50 * d).toInt(), 1.3f))
            })
        }
        L.translateTree(box); Icons.applyTree(box)
        box.background = bg(panel, accent, 2f, 22f)
        dlg = AlertDialog.Builder(this).setView(box).create().apply { setCanceledOnTouchOutside(false) } // a stray tap after the celebration must not close it
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.show()
    }

    /** Right after a prestige: the new rank, its boost and its rewards (Use it now: the Garage). */
    private fun showPrestigeReveal() {
        val p = (20 * d).toInt()
        val col = Xp.rankColor()
        val rewards = Xp.items.filter { it.rank == Xp.prestige.coerceAtMost(5) }
        lateinit var dlg: AlertDialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(p, p, p, p)
            addView(label(L.t("PRESTIGE"), 14f, bold = true).apply { setTextColor(readable(accent)); letterSpacing = 0.3f })
            addView(label("◆", 64f, bold = true).apply { setTextColor(col); gravity = Gravity.CENTER })
            addView(label(L.t(Xp.rankName()), 30f, bold = true).apply { setTextColor(ink); typeface = theme.typeface; gravity = Gravity.CENTER })
            addView(label(L.t("+${Xp.boostPct}% XP on every drive, for good"), 15f).apply { setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p / 4, 0, p / 2) })
            for (u in rewards) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(GarageSwatch(context, u, theme.look), LinearLayout.LayoutParams((70 * d).toInt(), (48 * d).toInt()).apply { marginEnd = p / 2 })
                addView(label(L.t(u.name), 16f, bold = true).apply { setTextColor(ink) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 3 })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
                addView(pill("Later") { dlg.dismiss() }.apply { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 14f) },
                    LinearLayout.LayoutParams(0, (50 * d).toInt(), 1f).apply { marginEnd = p / 2 })
                if (rewards.isNotEmpty()) addView(pill("Use it now") { dlg.dismiss(); Xp.choose(rewards.first()); garageTab = rewards.first().kind; openSheet("garage") }
                    .apply { setTextColor(onAccent(accent)); background = bg(accent, radius = 14f) }, LinearLayout.LayoutParams(0, (50 * d).toInt(), 1.3f))
            })
        }
        L.translateTree(box); Icons.applyTree(box)
        box.background = bg(panel, col, 2.5f, 22f)
        dlg = AlertDialog.Builder(this).setView(box).create()
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.show()
    }

    /** What a trip earned, line by line. */
    private fun showXpParts(trip: Explorer.Trip) {
        val lines = if (trip.parts.isEmpty()) listOf("+${trip.xp} XP")
            else trip.parts.map { "${if (it.xp > 0) "+${it.xp}" else "0"}  ${L.t(it.label)}" + (if (it.note.isNotBlank()) " · " + L.t(it.note) else "") }
        GameBuilder(this@MainActivity).setTitle(L.t("+${trip.xp} XP on this trip"))
            .setMessage(lines.joinToString("\n") + "\n\n" + L.t("No XP for speed: race places count only after a clean drive."))
            .setPositiveButton("OK", null).showT()
    }

    /** The arrival effect from the Garage, over the arrival card, once (you've stopped). */
    private inner class ArrivalFx(ctx: android.content.Context, val id: String) : View(ctx) {
        private val start = android.os.SystemClock.uptimeMillis()
        private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rnd = java.util.Random(7)
        private val parts = Array(if (id == "arrival_lights") 18 else 70) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()) }
        private val cols = when (id) {
            "arrival_confetti" -> intArrayOf(Color.rgb(255, 80, 120), Color.rgb(80, 200, 255), Color.rgb(255, 210, 60), Color.rgb(120, 230, 120))
            "arrival_fireworks" -> intArrayOf(Color.rgb(255, 160, 40), Color.rgb(255, 80, 200), Color.rgb(255, 240, 160))
            else -> intArrayOf(accent, Color.WHITE, theme.player)
        }
        init { isClickable = false; isFocusable = false }
        override fun onDraw(c: Canvas) {
            val t = (android.os.SystemClock.uptimeMillis() - start) / 2600f
            if (t >= 1f) { visibility = View.GONE; return }
            val w = width.toFloat(); val h = height.toFloat()
            pt.alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            when (id) {
                "arrival_confetti" -> parts.forEachIndexed { i, p ->
                    pt.color = cols[i % cols.size]; pt.alpha = ((1f - t) * 255).toInt()
                    val x = w * p[0] + kotlin.math.sin((t * 6 + p[2] * 6).toDouble()).toFloat() * 14 * d
                    val y = -20 * d + (h + 40 * d) * (t * (0.6f + p[1] * 0.6f))
                    c.save(); c.rotate(t * 360 * (p[3] - 0.5f) * 4, x, y); c.drawRect(x - 4 * d, y - 2 * d, x + 4 * d, y + 2 * d, pt); c.restore()
                }
                "arrival_fireworks" -> for (b in 0 until 3) {
                    val bt = ((t - b * 0.18f) / 0.6f).coerceIn(0f, 1f); if (bt <= 0f) continue
                    val bx = w * (0.25f + b * 0.25f); val by = h * (0.25f + (b % 2) * 0.15f)
                    for (k in 0 until 22) {
                        val a = k * Math.PI * 2 / 22; val r = bt * w * 0.22f
                        pt.color = cols[(k + b) % cols.size]; pt.alpha = ((1f - bt) * 255).toInt()
                        c.drawCircle(bx + (r * kotlin.math.cos(a)).toFloat(), by + (r * kotlin.math.sin(a)).toFloat() + bt * bt * 30 * d, 3 * d, pt)
                    }
                }
                else -> parts.forEachIndexed { i, p -> // light show: beams sweeping from the bottom
                    pt.color = cols[i % cols.size]; pt.alpha = ((1f - t) * 90).toInt()
                    pt.strokeWidth = 10 * d
                    val a = Math.toRadians(-90.0 + kotlin.math.sin((t * 5 + p[0] * 6).toDouble()) * 50)
                    val x0 = w * p[1]; val y0 = h
                    c.drawLine(x0, y0, x0 + (h * kotlin.math.cos(a)).toFloat(), y0 + (h * kotlin.math.sin(a)).toFloat(), pt)
                }
            }
            postInvalidateOnAnimation()
        }
    }

    private fun showProfile() { openSheet("progress") }

    @Suppress("unused")
    private fun showProfileOld() {
        val (have, of) = Explorer.progress()
        GameBuilder(this@MainActivity)
            .setTitle("Explorer · level ${Explorer.level}")
            .setMessage("${Explorer.xp} XP in total ($have / $of to the next level)\n" +
                "${Explorer.foundCount} landmarks discovered\n\n" +
                "Every trip of 200 m or more earns XP (more for longer drives and for taking faster routes). " +
                "Pass close to museums, monuments, viewpoints and other landmarks to discover them: " +
                "+25 XP each. In the Catch 'Em Trail style they show on the map as blue ? markers.")
            .setPositiveButton("OK", null).showT()
    }

    private fun showAbout() {
        GameBuilder(this@MainActivity)
            .setTitle("GameRoute build ${shownBuild()}")
            .setMessage("Map data and speed cameras © OpenStreetMap contributors\nVector tiles: OpenFreeMap\nTraffic, incidents, speed limits: TomTom\nRouting: OSRM (TomTom as backup)\nSearch: Photon (komoot), Nominatim\nVoice: your phone's text-to-speech")
            .setPositiveButton("OK", null).showT()
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) Toast.makeText(this, L.t("Checking for updates…"), Toast.LENGTH_SHORT).show()
        io.execute {
            val r = try { Updater.check(this) } catch (e: Exception) { null }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (r == null) {
                    if (manual) Toast.makeText(this, L.t("You have the latest version (build ${shownBuild()})"), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                GameBuilder(this@MainActivity)
                    .setTitle("Update available")
                    .setMessage("${r.tag} is ready (you have build ${shownBuild()}). Download and install it?")
                    .setPositiveButton("Update") { _, _ -> downloadAndInstall(r) }
                    .setNegativeButton("Later", null)
                    .showT()
            }
        }
    }

    private fun downloadAndInstall(r: Updater.Release) {
        Toast.makeText(this, L.t("Downloading update…"), Toast.LENGTH_LONG).show()
        io.execute {
            val ok = try { Updater.download(this, r) {}; true } catch (e: Exception) { false }
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, L.t("Download failed: check internet"), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val viaUnknownAuto = Updater.hasUnknownAuto(this)
                GameBuilder(this@MainActivity)
                    .setTitle("Install update")
                    .setMessage(
                        if (viaUnknownAuto)
                            "Make sure Shizuku is running, then tap Install in UnknownAuto. " +
                                "This keeps GameRoute visible in Android Auto."
                        else
                            "UnknownAuto isn't installed, so Android's normal installer will be used. " +
                                "The update will work on the phone, but Android Auto may hide GameRoute " +
                                "until you reinstall it through UnknownAuto."
                    )
                    .setPositiveButton("Continue") { _, _ ->
                        try { Updater.install(this) } catch (e: Exception) {
                            Toast.makeText(this, L.t("Couldn't open the installer: ${e.message}"), Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle & compass

    override fun onStart() {
        super.onStart()
        if (LocationTracker.hasPermission(this)) LocationTracker.start(this)
        NavEngine.changed += stateListener
        MapState.changed += stateListener
        Voice.changed += stateListener
        Explorer.changed += stateListener
        Later.changed += stateListener
        Parked.changed += stateListener
        Alerts.changed += stateListener
        Convoy.changed += stateListener
        LocationTracker.changed += compassCheck
        NavEngine.changed += compassCheck
        Friends.changed += friendsListener
        Friends.arrived += friendsArrived
        InvitePop.changed += invitePopListener
        LocationStatus.changed += locListener
        Duties.changed += dutyListener
        LocationStatus.phoneVisible = true
        LocationStatus.check(); syncLocCard()
        Friends.setVisible(true)
        syncInvitePop()
        refreshChrome()
        main.postDelayed({ maybeAskInvite() }, 800)
    }

    override fun onStop() {
        NavEngine.changed -= stateListener
        MapState.changed -= stateListener
        Voice.changed -= stateListener
        Explorer.changed -= stateListener
        Later.changed -= stateListener
        Parked.changed -= stateListener
        Alerts.changed -= stateListener
        Convoy.changed -= stateListener
        LocationTracker.changed -= compassCheck
        NavEngine.changed -= compassCheck
        Friends.changed -= friendsListener
        Friends.arrived -= friendsArrived
        InvitePop.changed -= invitePopListener
        LocationStatus.changed -= locListener
        Duties.changed -= dutyListener
        LocationStatus.phoneVisible = false
        Friends.setVisible(false)
        main.removeCallbacks(ticker)
        HomeWidget.refresh(this)
        // in the background and not navigating: let the GPS rest
        LocationTracker.release(this, "phone")
        if (!LocationTracker.inUse && !NavEngine.active) Voice.shutdown()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Duties.refresh()
        syncDutyCard()
        updateCompass()
    }

    override fun onPause() {
        resumed = false
        updateCompass()
        super.onPause()
    }

    private var resumed = false
    private var compassOn = false

    /**
     * The compass turns the arrow only when standing still and not navigating; while driving the
     * heading comes from the GPS. So the sensor (gyro + magnetometer) runs only then: battery.
     */
    private fun updateCompass() {
        val want = resumed && !NavEngine.active && LocationTracker.speedMps < 1.5f
        if (want == compassOn) return
        compassOn = want
        val sm = getSystemService(SensorManager::class.java) ?: return
        if (want) sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 200_000) }
        else { sm.unregisterListener(this); LocationTracker.setCompass(null); lastCompass = -999f }
    }
    private val compassCheck: () -> Unit = { updateCompass() }

    private val rot = FloatArray(9)
    private val remap = FloatArray(9)
    private val orient = FloatArray(3)
    private var lastCompass = -999f

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        @Suppress("DEPRECATION")
        val r = windowManager.defaultDisplay.rotation
        val (ax, ay) = when (r) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rot, ax, ay, remap)
        SensorManager.getOrientation(remap, orient)
        val deg = ((Math.toDegrees(orient[0].toDouble()) + 360) % 360).toFloat()
        if (abs(Geo.angleDiff(lastCompass, deg)) > 3f) { // hand tremor isn't worth a redraw
            lastCompass = deg
            LocationTracker.setCompass(deg)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private val SEARCH_TOKEN = Any()
    }
}

/** The phone's map view: redraws on changes, animates smoothly, pinch to zoom, drag to look around. */
class GameMapView(ctx: Context) : View(ctx) {
    private val renderer = MapRenderer()
    /** The bottom of the turn box (or the top of the map), on the last frame. */
    val bannerBottom: Float get() = renderer.bannerBottomNow
    internal val rendererForTest: MapRenderer get() = renderer
    private val area = Rect()
    var insetTop = 0
    var insetBottom = 0
    private var shownInsetBottom = -1f
    private var lastTouchAt = android.os.SystemClock.uptimeMillis()

    /**
     * Rain and snow pause after two minutes of nobody touching the map with no route and the car
     * standing still (the phone left on a table): they were redrawing the whole map 20-30 times a
     * second for nobody. A touch, a route or moving off brings them straight back.
     */
    private fun effectsResting() = !NavEngine.active && LocationTracker.speedMps < 1f &&
        android.os.SystemClock.uptimeMillis() - lastTouchAt > 120_000
    /** Called when a finger first touches the map (closes search). */
    var onTouchStart: (() -> Unit)? = null
    /** Where the middle of the map is right now. */
    fun center(): LatLng? = renderer.camera?.let { renderer.toMap(it.cx, it.cy, it) }

    /** A tap on a place icon / on empty map; a long press somewhere. */
    var onPlaceTap: ((Place) -> Unit)? = null
    var onMapTap: (() -> Unit)? = null
    var onConvoyTap: (() -> Unit)? = null
    var onFriendTap: ((Convoy.Member) -> Unit)? = null
    var onLongPress: ((LatLng) -> Unit)? = null
    /** The place whose card is open (ring / pin on the map). */
    var selected: Place?
        get() = renderer.selectedPlace
        set(v) { renderer.selectedPlace = v; invalidate() }
    private var held = false
    private val hold = Runnable {
        val cam = renderer.camera ?: return@Runnable
        if (dragging || scaling || rotating) return@Runnable
        held = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke(renderer.toMap(downX, downY, cam))
    }
    private val redraw: () -> Unit = { postInvalidateOnAnimation() }
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var scaling = false
    private var rotating = false
    private var lastAngle = 0f
    private var twist = 0f
    private var downX = 0f
    private var downY = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            MapState.zoomByFactor(detector.scaleFactor)
            return true
        }
    })

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        TileCache.loaded += redraw
        LocationTracker.changed += redraw
        NavEngine.changed += redraw
        MapState.changed += redraw
        Convoy.changed += redraw
    }

    override fun onDetachedFromWindow() {
        TileCache.loaded -= redraw
        LocationTracker.changed -= redraw
        NavEngine.changed -= redraw
        MapState.changed -= redraw
        Convoy.changed -= redraw
        super.onDetachedFromWindow()
    }

    private fun angle(e: MotionEvent) =
        Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    private var touching = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouchAt = android.os.SystemClock.uptimeMillis()
        touching = e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL
        if (MapState.gesture != (touching && (dragging || scaling || rotating))) { MapState.gesture = touching && (dragging || scaling || rotating); if (!MapState.gesture) invalidate() }
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchStart?.invoke()
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                dragging = false; scaling = false; rotating = false; twist = 0f
                held = false
                removeCallbacks(hold); postDelayed(hold, 550)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { removeCallbacks(hold); if (e.pointerCount == 2) { lastAngle = angle(e); twist = 0f } }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    // Two-finger twist rotates the map (after a small dead zone, so pinching stays clean).
                    val a = angle(e)
                    val delta = Geo.angleDiff(lastAngle, a)
                    lastAngle = a
                    twist += delta
                    if (!rotating && abs(twist) > 12f) rotating = true
                    if (rotating) {
                        val cam = renderer.camera
                        if (cam != null) MapState.rotateBy(-delta, cam.bearing)
                    }
                } else if (!scaling && !rotating) {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    if (dragging || abs(e.x - downX) + abs(e.y - downY) > 12 * resources.displayMetrics.density) {
                        if (!dragging) removeCallbacks(hold)
                        dragging = true
                        val cam = renderer.camera
                        if (cam != null) {
                            // Move the camera so the point under the finger stays under it.
                            MapState.pan(renderer.toMap(cam.cx - dx, cam.cy - dy, cam), cam.bearing)
                        }
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                val tap = e.actionMasked == MotionEvent.ACTION_UP && !dragging && !scaling && !rotating && !held
                when {
                    // Tap on the compass: back to driving direction / north.
                    tap && renderer.compassBounds.contains(e.x, e.y) -> MapState.clearRotation()
                    tap && renderer.convoyStrip.contains(e.x, e.y) -> onConvoyTap?.invoke()
                    tap && renderer.friendAt(e.x, e.y) != null -> renderer.friendAt(e.x, e.y)?.let { onFriendTap?.invoke(it) }
                    tap -> renderer.placeAt(e.x, e.y)?.let { onPlaceTap?.invoke(it.place()) } ?: onMapTap?.invoke()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        // the bottom edge eases to where it's going (a sheet sliding up or down): the arrow and
        // the speed box glide with it instead of hopping
        val diff = insetBottom - shownInsetBottom
        shownInsetBottom = if (Math.abs(diff) < 1.5f || shownInsetBottom < 0f) insetBottom.toFloat() else shownInsetBottom + diff * 0.3f
        if (shownInsetBottom != insetBottom.toFloat()) postInvalidateOnAnimation()
        area.set(0, insetTop, width, height - shownInsetBottom.toInt())
        val t0 = android.os.SystemClock.uptimeMillis()
        renderer.draw(canvas, width, height, area, resources.displayMetrics.density, hud = true)
        Perf.frameDrawn(android.os.SystemClock.uptimeMillis() - t0)
        // full frame rate under the fingers; otherwise Settings › Performance sets the pace
        // (a 120 Hz phone would otherwise draw the whole map 120 times a second while driving)
        if (renderer.animating) {
            if (MapState.gesture || renderer.interacting) postInvalidateOnAnimation()
            else postInvalidateDelayed(Perf.frameDelay(LocationTracker.speedMps > 1f))
        } else if (renderer.effectsMoving && !effectsResting()) {
            // standing still with only the rain or snow moving: steady enough to look like rain
            postInvalidateDelayed(Perf.effectsDelay())
        }
    }
}

/** A thin bar showing where along a route the traffic is. */
class TrafficBar(ctx: Context, private val o: RouteOption) : View(ctx) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = h / 2
        p.color = if (o.trafficSeconds != null) Color.rgb(47, 210, 122) else Color.argb(90, 150, 150, 150)
        c.drawRoundRect(0f, 0f, w, h, r, r, p)
        val len = o.length.takeIf { it > 0 } ?: return
        for (s in o.traffic) {
            p.color = when {
                s.value >= 4 -> Color.rgb(140, 20, 30)
                s.value == 3 -> Color.rgb(230, 50, 60)
                s.value == 2 -> Color.rgb(255, 140, 40)
                else -> Color.rgb(255, 200, 60)
            }
            val a = (s.start / len * w).toFloat(); val b = maxOf((s.end / len * w).toFloat(), a + h)
            c.drawRect(a, 0f, b.coerceAtMost(w), h, p)
        }
    }
}
