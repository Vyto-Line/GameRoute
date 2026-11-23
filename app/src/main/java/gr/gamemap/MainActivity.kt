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

    internal lateinit var map: GameMapView
    internal lateinit var topArea: LinearLayout
    internal lateinit var searchCard: LinearLayout
    internal lateinit var search: EditText
    internal lateinit var searchIcon: TextView
    internal lateinit var clearBtn: TextView
    internal lateinit var menuBtn: TextView
    /** Friends, in the search bar: red number = requests and invites waiting, green dot = a friend online. */
    internal lateinit var peopleBtn: android.widget.FrameLayout
    internal lateinit var peopleIcon: TextView
    internal lateinit var peopleBadge: TextView
    internal lateinit var peopleDot: View
    internal lateinit var chipsScroll: HorizontalScrollView
    internal lateinit var chips: LinearLayout
    internal lateinit var suggestScroll: ScrollView
    internal lateinit var suggestList: LinearLayout
    internal lateinit var controls: LinearLayout
    internal lateinit var laterPill: TextView
    internal lateinit var youBtn: View
    /** While driving the buttons fade; a tap on the map brings them back until then. */
    internal var controlsUntil = 0L
    internal lateinit var styleBtn: TextView
    internal lateinit var recenterBtn: TextView
    internal lateinit var muteBtn: TextView
    internal lateinit var overviewBtn: TextView
    internal lateinit var navMenuBtn: TextView
    internal lateinit var reportBtn: TextView
    internal lateinit var askCard: LinearLayout
    internal lateinit var askText: TextView
    internal var askShown: Alerts.Report? = null
    internal lateinit var homeSheet: LinearLayout
    internal lateinit var homeTitle: TextView
    internal lateinit var homeCards: LinearLayout
    /** The slim bar's words, shown when Saved & recent is tucked away (46b). */
    internal lateinit var homeHandle: TextView
    /** Saved & recent pulled up (remembered); tucks itself away after you pick a place. */
    internal var homeOpen: Boolean
        get() = prefs().getBoolean("homeOpen", false)
        set(v) { slideHome(v) }

    /**
     * The sheet is always laid out open; tucked away it slides down so only its top bar shows.
     * Sliding (not resizing) keeps the cards whole while it moves, and nothing re-lays out.
     */
    internal var homeAnim: android.animation.ValueAnimator? = null
    internal var homeDragging = false

    /** How far down the sheet slides when tucked away (0 when there's nothing to tuck). */
    internal fun homeClosedOffset(): Float {
        if (homeSheet.height == 0 || homeTitle.visibility != View.VISIBLE) return 0f
        val header = homeTitle.parent as View
        return (homeSheet.height - header.bottom - homeSheet.paddingBottom + 6 * d).coerceAtLeast(0f)
    }

    /** 0 = tucked away, 1 = open: moves the sheet, cross-fades the bar's words and the title. */
    internal fun setHomeFraction(f: Float) {
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

    internal fun homeFraction(): Float {
        val off = homeClosedOffset()
        return if (off <= 0f) 1f else (1f - homeSheet.translationY / off).coerceIn(0f, 1f)
    }

    /** Glides from wherever the sheet is (open, tucked, or where the finger let go) to [open]. */
    internal fun slideHome(open: Boolean, velocity: Float = 0f) {
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
    internal var homeDragStartF = 0f
    internal fun homeDragStart() { homeAnim?.cancel(); homeDragging = true; homeDragStartF = homeFraction() }
    internal fun homeDragTo(dy: Float) {
        val off = homeClosedOffset()
        if (off > 0f) setHomeFraction((homeDragStartF - dy / off).coerceIn(0f, 1f))
    }
    internal fun homeDragEnd(dy: Float, vy: Float) {
        homeDragging = false
        slideHome(when { vy < -0.5f -> true; vy > 0.5f -> false; else -> homeFraction() > 0.5f }, vy * 1f)
    }
    internal lateinit var navCard: LinearLayout
    internal lateinit var etaBig: TextView
    internal lateinit var etaTraffic: TextView
    internal lateinit var etaSmall: TextView
    internal lateinit var endBtn: TextView
    internal lateinit var shareBtn: TextView
    internal lateinit var preview: LinearLayout
    internal lateinit var previewTitle: TextView
    internal lateinit var previewSub: TextView
    internal lateinit var previewList: LinearLayout
    internal lateinit var previewClose: TextView
    internal lateinit var previewSave: TextView
    internal lateinit var previewBack: TextView
    internal lateinit var trafficBtn: TextView
    internal lateinit var laterBtn: TextView
    internal lateinit var convoyNewBtn: TextView
    internal lateinit var convoyBtn: TextView
    internal lateinit var startBtn: TextView
    internal lateinit var offerCard: LinearLayout
    internal lateinit var offerTitle: TextView
    internal lateinit var offerSub: TextView
    internal lateinit var offerVia: TextView
    internal lateinit var keepBtn: TextView
    internal lateinit var switchBtn: TextView
    internal lateinit var sheetOverlay: FrameLayout
    internal lateinit var tripOverlay: FrameLayout
    internal lateinit var placeCard: LinearLayout
    /** The place (icon tapped or pin dropped) whose card is showing. */
    internal var placeShown: Place? = null
    internal var placeEta: String? = null
    internal lateinit var tripCard: LinearLayout
    internal var shownTrip: Explorer.Trip? = null
    internal lateinit var sheetHost: LinearLayout
    internal var sheetKind = ""
    internal val fabs = mutableListOf<TextView>()

    internal val io = Executors.newFixedThreadPool(2)
    internal val main = Handler(Looper.getMainLooper())
    internal var d = 1f
    internal var insets = Rect()
    internal var querySeq = 0
    internal var lastResults: List<Place> = emptyList()
    /** "home" / "work" while the user is choosing that saved place in search. */
    internal var pickFor: String? = null
    internal var etaAskedAt = 0L
    internal var etaVersion = 0
    internal var lastHomeKey = ""
    internal var lastPreviewKey = ""

    internal val stateListener: () -> Unit = { refreshChrome() }
    internal val ticker = object : Runnable {
        override fun run() {
            if (NavEngine.offer != null) { refreshChrome(); main.postDelayed(this, 1000) }
        }
    }

    internal lateinit var welcome: FrameLayout
    internal var welcomePage = 0
    internal var wantedPermissions = arrayOf<String>()

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

    internal var wallAnim: android.animation.ObjectAnimator? = null

    internal var welcomeSwipe: android.view.GestureDetector? = null

    /**
     * The welcome pages slide: the old page drifts out, the new one glides in, its picture a
     * little behind its text (parallax), and the words rise into place. The first page's wall of
     * map styles keeps floating gently.
     */
    internal fun animateWelcome(old: View?, col: View, top: View, text: View, page: Int, prevPage: Int, firstShow: Boolean) {
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

    internal val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LocationTracker.start(this)
        LocationStatus.check()
    }

    // ---------------------------------------------------------------- location off / not allowed

    internal lateinit var locCard: LinearLayout
    internal val locListener: () -> Unit = { runOnUiThread { syncLocCard() } }
    internal val dutyListener: () -> Unit = { runOnUiThread { dutyKey = "?"; moreKey = ""; refreshChrome(); map.invalidate() } }

    internal lateinit var dutyCard: LinearLayout
    internal var dutyKey = ""

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

    // ------------------------------------------------------------------ look helpers

    internal val theme get() = MapState.theme
    internal val ink get() = Ui.ink(theme)
    internal val panel: Int get() = Ui.panel(theme)
    internal val accent get() = theme.route
    /** Secondary text (strength 255 = full ink); thin lines and fills below 120 stay faint. */
    internal fun dim(a: Int = 185) = if (a < 120) Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink)) else Ui.dim(theme, a)
    internal fun bg(fill: Int, stroke: Int = 0, strokeDp: Float = 0f, radius: Float = 16f, topOnly: Boolean = false, pill: Boolean = false) =
        HudDrawable(theme.hud, fill, d, radius, stroke, strokeDp, topOnly, pill, glow = theme.route)
    internal fun luminance(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
    internal fun onAccent(c: Int) = if (luminance(c) > 0.5) Color.rgb(15, 18, 22) else Color.WHITE
    internal fun darken(c: Int) = Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10)
    internal val lightPanel get() = luminance(panel) > 0.6
    internal fun readable(c: Int) = Ui.onPanel(theme, c)

    internal fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
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
    internal fun TextView.rankMark(): TextView {
        val t = text?.toString() ?: return this
        if (Xp.prestige <= 0 || !t.startsWith("◆")) return this
        text = android.text.SpannableString(t).apply {
            setSpan(android.text.style.ForegroundColorSpan(Xp.rankColor()), 0, 1, 0)
            setSpan(android.text.style.RelativeSizeSpan(1.2f), 0, 1, 0)
        }
        return this
    }

    internal fun label(t: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    // ------------------------------------------------------------------ search & quick places

    internal var moreKey = ""

    // ------------------------------------------------------------------ pharmacies (on duty first)

    internal var pharmShown: List<Place>? = null

    // ------------------------------------------------------------------ fuel nearby (by price)

    /** The stations found (unfiltered); the list re-sorts from it when a chip or the sort changes. */
    internal var fuelShown: List<Place>? = null

    // ------------------------------------------------------------------ home sheet (recent places)

    internal var exploreCard: LinearLayout? = null

    internal fun buildHomeSheet() {
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

    // ------------------------------------------------------------------ right-hand buttons

    // ------------------------------------------------------------------ driving card

    internal lateinit var stopsRow: LinearLayout
    internal var stopsKey = ""

    // ------------------------------------------------------------------ route preview

    internal var infoAsked: Place? = null
    internal var infoFuel: String? = null

    // ------------------------------------------------------------------ place card (tap / hold)

    // ------------------------------------------------------------------ GameRoute's own pop-ups

    /** Pop-ups open now (newest last): the back button closes the newest first. */
    internal val popups = ArrayList<GameDialog>()

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
    internal var navBottom = 0

    internal fun sharePlace(pl: Place) {
        val url = "https://www.google.com/maps/search/?api=1&query=${pl.pos.lat},${pl.pos.lon}"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${pl.name}\n${pl.address}\n$url".replace("\n\n", "\n"))
        }
        startActivity(Intent.createChooser(send, "Share place"))
    }

    internal fun showReviews(place: Place) {
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

    internal fun renderPreview() {
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

    // ------------------------------------------------------------------ arrival card

    // ------------------------------------------------------------------ state → UI

    /** Colours everything to match the map theme and shows what fits the moment. */
    internal var lastLookKey = ""
    internal var tripLook = ""
    /** A level-up waiting to be shown once the arrival card is closed. */
    internal var pendingLevelFrom = 0
    internal var tripFxFor: Explorer.Trip? = null

    internal var screenKept = false

    internal fun refreshChrome() {
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

    internal var shareBtnKey = ""
    internal val quietTick = Runnable { refreshChrome() }
    internal var laterShowing = false

    /** What waited while you drove, one list. */
    internal fun showLater() {
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

    internal fun updateMapInsets() {
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

    /** Typed in search before "Can't find it?": the label for the pin you drop next. */
    internal var pinName: String? = null

    // ------------------------------------------------------------------ bottom sheets

    /** Where the open sheet was opened from (You › Settings › …): its back button goes there. */
    internal val sheetStack = ArrayList<String>()
    internal var sheetNoPush = false

    /** Where the style list was scrolled to (picking a style redraws the sheet: it stays put). */
    internal var styleScroll = 0

    /** ☰ while driving: the things that used to be separate round buttons, plus Settings. */
    internal var quickKey = ""
    internal var styleKey = ""
    internal var settingsKey = ""
    internal var reportKey = ""

    /** Which settings page is open: null = the list of groups. */
    internal var settingsPage: String? = null

    internal class SettingsGroup(val key: String, val title: String, val summary: String, val icon: Int, val tint: Int)

    ) + (if (BuildConfig.TEST_BUILD) listOf(
        // test builds only (never in a public release): tools for trying things out
        SettingsGroup("dev", "Developer", "Test builds only: simulate drives, test friends, XP, celebrations, log", R.drawable.ic_set_adv, Color.rgb(219, 39, 119)),
    ) else emptyList())

    internal var settingsQuery = ""

    internal val TALK = arrayOf("Normal", "Fewer", "Turns only")

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

    internal val REQ_BACKUP_SAVE = 4101
    internal val REQ_BACKUP_OPEN = 4102

    /** Settings › Advanced › What's new: version 100. */
    internal val WHATS_NEW = listOf(
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
    internal val PERF_MODE = arrayOf("Auto", "Smooth", "Battery")
    internal val PERF_ANIM = arrayOf("Full", "Reduced", "Off")
    internal val PERF_SMOOTH = arrayOf("High", "Normal", "Low")

    // ------------------------------------------------------------------ convoy

    /** Asks for the name the group sees, once. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    internal var convoyKey = ""

    internal var proposalAsked = 0L

    /** Convoy sheet: the code box is open (Join with a code). */
    internal var convoyJoinOpen = false

    // ---------------------------------------------------------------- the convoy, step by step

    /** The "how" I picked on step 2 but haven't confirmed yet (null: as it is now). */
    internal var pendingMode: String? = null

    internal var inviteKey = ""

    // ------------------------------------------------------------------ friends

    /** The You button: your level as a ring, with a dot when something's waiting. */
    internal inner class YouRing(ctx: android.content.Context, val big: Boolean = false) : View(ctx) {
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

    internal var youKey = ""

    internal var friendsKey = ""

    internal val friendsListener: () -> Unit = { runOnUiThread { friendsKey = ""; refreshChrome() } }
    internal val friendsArrived: () -> Unit = { runOnUiThread {
        // driving: friends' news waits (the request or invite is in Together when you stop)
        if (Later.driving && Friends.ready && Friends.core.started) {
            val req = Friends.core.requests.size
            if (req > 0) Later.add("👥 " + L.t(if (req == 1) "1 friend request: open Together" else "$req friend requests: open Together"))
        }
        maybeAskInvite()
    } }
    internal var inviteAsked = ""

    /** One avatar, drawn (a lock over it when [locked]). */
    internal inner class AvatarView(ctx: android.content.Context, val a: Avatars.Avatar, var color: Int, val locked: Boolean = false, val chosen: Boolean = false) : View(ctx) {
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

    /**
     * A scrolling part of a sheet, at most [frac] of the screen, and less when the keyboard is up
     * (then the sheet sits above the keyboard and this part shrinks: what you type stays in view).
     */
    internal inner class SheetScroll(private val frac: Double) : ScrollView(this@MainActivity) {
        override fun onMeasure(w: Int, h: Int) {
            val maxH = (resources.displayMetrics.heightPixels * frac).toInt()
            val lim = if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) maxH else minOf(maxH, MeasureSpec.getSize(h))
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(lim, MeasureSpec.AT_MOST))
        }
    }

    // ---------------------------------------------------------------- the invite card

    internal lateinit var inviteClip: FrameLayout
    internal var inviteCard: View? = null
    internal var inviteFor: FriendCore.Invite? = null

    internal val invitePopListener: () -> Unit = { runOnUiThread { syncInvitePop() } }

    internal var blockedDialog: GameDialog? = null

    internal var offlineKey = ""
    internal val offlineListener: () -> Unit = { runOnUiThread { if (sheetKind == "offline") { offlineKey = ""; renderOffline() } } }

    internal var tripsKey = ""

    /** Past trips, newest first, with totals at the top. */
    internal var recapYear = false
    internal val recapShown = HashSet(Recap.LINES.map { it.first })
    internal var recapKey = ""

    internal var progressKey = ""

    internal var garageKey = ""
    internal var garageTab = "arrow"

    /** A small drawing of a Garage item. */
    internal inner class GarageSwatch(ctx: android.content.Context, val item: Xp.Item, val look: Look) : View(ctx) {
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
                        in Flags.ids -> Flags.draw(c, item.id, cx, cy + s * (if (item.id == "flag_beacon") 1.6f else if (item.id == "flag_x") 0.2f else 1.1f), s / (if (item.id == "flag_beacon") 70f else 30f), Xp.rankColor())
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

    /** Only now counted as celebrated: once it has really been shown (not when it starts). */
    internal var celebrating = false

    /** The arrival effect from the Garage, over the arrival card, once (you've stopped). */
    internal inner class ArrivalFx(ctx: android.content.Context, val id: String) : View(ctx) {
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

    internal var resumed = false
    internal var compassOn = false

    /**
     * The compass turns the arrow only when standing still and not navigating; while driving the
     * heading comes from the GPS. So the sensor (gyro + magnetometer) runs only then: battery.
     */
    internal fun updateCompass() {
        val want = resumed && !NavEngine.active && LocationTracker.speedMps < 1.5f
        if (want == compassOn) return
        compassOn = want
        val sm = getSystemService(SensorManager::class.java) ?: return
        if (want) sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 200_000) }
        else { sm.unregisterListener(this); LocationTracker.setCompass(null); lastCompass = -999f }
    }
    internal val compassCheck: () -> Unit = { updateCompass() }

    internal val rot = FloatArray(9)
    internal val remap = FloatArray(9)
    internal val orient = FloatArray(3)
    internal var lastCompass = -999f

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
    internal val renderer = MapRenderer()
    /** The bottom of the turn box (or the top of the map), on the last frame. */
    val bannerBottom: Float get() = renderer.bannerBottomNow
    internal val rendererForTest: MapRenderer get() = renderer
    internal val area = Rect()
    var insetTop = 0
    var insetBottom = 0
    internal var shownInsetBottom = -1f
    internal var lastTouchAt = android.os.SystemClock.uptimeMillis()

    /**
     * Rain and snow pause after two minutes of nobody touching the map with no route and the car
     * standing still (the phone left on a table): they were redrawing the whole map 20-30 times a
     * second for nobody. A touch, a route or moving off brings them straight back.
     */
    internal fun effectsResting() = !NavEngine.active && LocationTracker.speedMps < 1f &&
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
    internal var held = false
    internal val hold = Runnable {
        val cam = renderer.camera ?: return@Runnable
        if (dragging || scaling || rotating) return@Runnable
        held = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke(renderer.toMap(downX, downY, cam))
    }
    internal val redraw: () -> Unit = { postInvalidateOnAnimation() }
    internal var lastX = 0f
    internal var lastY = 0f
    internal var dragging = false
    internal var scaling = false
    internal var rotating = false
    internal var lastAngle = 0f
    internal var twist = 0f
    internal var downX = 0f
    internal var downY = 0f

    internal val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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

    internal fun angle(e: MotionEvent) =
        Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    internal var touching = false

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
    internal val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
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
