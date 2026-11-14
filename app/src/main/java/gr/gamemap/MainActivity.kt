package gr.gamemap

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var suggestScroll: ScrollView
    private lateinit var suggestList: LinearLayout
    private lateinit var controls: LinearLayout
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
                    welcome.animate().alpha(0f).setDuration(350).withEndAction {
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
            old.animate().translationX(-dir * w * 0.45f).alpha(0f).setDuration(340).setInterpolator(ease)
                .withEndAction { welcome.removeView(old) }.start()
            col.translationX = dir * w
            col.animate().translationX(0f).setDuration(420).setInterpolator(ease).start()
            top.translationX = dir * w * 0.35f
            top.animate().translationX(0f).setDuration(620).setInterpolator(ease).start()
        } else if (firstShow) {
            top.alpha = 0f; top.translationY = 60 * d
            top.animate().alpha(1f).translationY(0f).setDuration(700).setInterpolator(ease).start()
        }
        text.alpha = 0f; text.translationY = 28 * d
        text.animate().alpha(1f).translationY(0f).setStartDelay(if (old != null) 160 else 260).setDuration(460).setInterpolator(ease).start()
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
    }

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
        map.onPlaceTap = { if (!NavEngine.planning) showPlace(it, dropped = false) }
        map.onMapTap = { if (placeShown != null) closePlace() }
        map.onLongPress = { p -> if (!NavEngine.planning) dropPin(p) }
        map.onConvoyTap = { openSheet("convoy") }
        map.onFriendTap = { m -> if (!NavEngine.planning) showFriend(m.id) }

        val root = FrameLayout(this).apply {
            addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(homeSheet, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            addView(topArea, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(controls, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
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
        root.viewTreeObserver.addOnPreDrawListener { L.translateTree(root); true }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, wi ->
            val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = wi.getInsets(WindowInsetsCompat.Type.ime())
            insets.set(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            val p = (10 * d).toInt()
            topArea.setPadding(bars.left + p, bars.top + p / 2, bars.right + p, 0)
            controls.setPadding(0, 0, bars.right + p, 0)
            navCard.setPadding(bars.left + 2 * p, p + p / 2, bars.right + 2 * p, bars.bottom + p + p / 2)
            preview.setPadding(bars.left + p + p / 2, p, bars.right + p + p / 2, bars.bottom + p + p / 2)
            homeSheet.setPadding(bars.left, p, bars.right, bars.bottom + p)
            placeCard.setPadding(bars.left + 2 * p, p + p / 2, bars.right + 2 * p, bars.bottom + 2 * p)
            sheetHost.setPadding(bars.left + 2 * p, p + p / 2, bars.right + 2 * p, bars.bottom + 2 * p)
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
                    welcome.visibility == View.VISIBLE -> if (welcomePage > 0) showWelcome(welcomePage - 1)
                    tripOverlay.visibility == View.VISIBLE -> NavEngine.dismissTrip()
                    placeShown != null -> closePlace()
                    askCard.visibility == View.VISIBLE -> Alerts.clearAsk()
                    sheetOverlay.visibility == View.VISIBLE && sheetKind == "settings" && settingsPage != null -> { settingsPage = null; refreshChrome() }
                    sheetOverlay.visibility == View.VISIBLE -> closeSheet()
                    suggestScroll.visibility == View.VISIBLE || pickFor != null -> closeSearch()
                    NavEngine.planning -> NavEngine.cancelPlan()
                    NavEngine.active -> AlertDialog.Builder(this@MainActivity)
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
        AlertDialog.Builder(this)
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
        searchCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = 6 * d
            minimumHeight = (56 * d).toInt()
            addView(searchIcon)
            addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(clearBtn)
            addView(menuBtn)
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
            addView(chipsScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(suggestScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
    }

    private fun renderChips() {
        chips.removeAllViews()
        fun chip(text: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
            val v = TextView(this).apply {
                this.text = text
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ink)
                gravity = Gravity.CENTER
                val p = (12 * d).toInt()
                setPadding(p + p / 4, 0, p + p / 4, 0)
                minHeight = (40 * d).toInt()
                elevation = 3 * d
                background = bg(panel, dim(80), 1f, 12f, pill = true)
                setOnClickListener { onClick() }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }
            chips.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, (40 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
        }
        // in a convoy: it comes first, with a quick way out
        if (Convoy.active) {
            chip("👥 Convoy · ${Convoy.members.size + 1}") { openSheet("convoy") }
            chip("✕ Leave convoy") { confirmLeaveConvoy() }
        }
        for (k in listOf("home", "work")) {
            val p = Places.saved(k)
            val name = if (k == "home") "🏠 Home" else "💼 Work"
            val eta = p?.let { Places.cachedEta(it) }?.let { " · ${NavEngine.formatDuration(it.seconds.toDouble())}" } ?: ""
            chip(if (p == null) "$name  +" else name + eta, onLong = { startPicking(k) }) {
                if (p == null) startPicking(k) else NavEngine.plan(this, p)
            }
        }
        // your labels ("👤 Dad"): one tap to go, hold to edit
        for (s in Places.labelled()) {
            val pl = s.place()
            val eta = Places.cachedEta(pl)?.let { " · ${NavEngine.formatDuration(it.seconds.toDouble())}" } ?: ""
            chip("${s.icon ?: "★"} ${s.label}$eta", onLong = { showSaveDialog(pl) {} }) { NavEngine.plan(this, pl) }
        }
        if (!Convoy.active) chip("👥 Convoy") { openSheet("convoy") }
        chip("⛽ Fuel") { showFuel() }
        chip("🍽 Food") { showNearby("FOOD NEARBY", listOf("restaurant", "fast_food", "cafe")) }
        chip("🅿 Parking") { showNearby("PARKING NEARBY", listOf("parking")) }
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
        homeSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = 8 * d
            isClickable = true
            addView(homeTitle)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(homeCards)
            })
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
                setOnClickListener { NavEngine.plan(this@MainActivity, pl) }
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
                        card.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(180).withEndAction { lastHomeKey = ""; refreshChrome() }.start()
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
        val anyCard = rec.isNotEmpty() || Parked.get() != null
        homeTitle.visibility = if (anyCard) View.VISIBLE else View.GONE
        homeCards.visibility = if (anyCard) View.VISIBLE else View.GONE
        renderExploreCard(near)
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
            addView(styleBtn); addView(trafficBtn); addView(reportBtn); addView(muteBtn); addView(overviewBtn); addView(recenterBtn); addView(navMenuBtn)
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
                AlertDialog.Builder(this@MainActivity).setMessage("Remove the stop at ${st.name}?")
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
        AlertDialog.Builder(this).setTitle("Share your trip")
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
                runOnUiThread { if (infoAsked == place && !isFinishing) { if (f != null) infoFuel = f; if (NavEngine.planning) renderPreview() } }
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
        if (pl.extra.isNotBlank()) placeCard.addView(label(pl.extra, 14f).apply {
            setTextColor(ink); setPadding(p, p / 2, p, p / 2); background = bg(dim(26), radius = 10f)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 3 })

        if (pl.icon == "👥") {
            // a friend in the convoy: drive to where they are now
            val navigating = NavEngine.active && !NavEngine.arrived
            placeCard.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0)
                addView(pill("➤  Drive to ${pl.name}") {
                    closePlace()
                    val m = Convoy.members.values.firstOrNull { it.name == pl.name }
                    NavEngine.start(this@MainActivity, (m?.let { Convoy.placeOf(it) } ?: pl)); MapState.recenter()
                }.apply {
                    setTextColor(onAccent(accent)); background = bg(accent, radius = 26f); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
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
        row.addView(pill(if (navigating) "Go instead" else "Routes") {
            closePlace(); if (navigating) NavEngine.start(this, pl) else NavEngine.plan(this, pl)
        }.apply {
            setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 26f)
            // one line, shrinking a little with big text or long words
            maxLines = 1; setPadding(paddingLeft / 2, paddingTop, paddingRight / 2, paddingBottom)
            setAutoSizeTextTypeUniformWithConfiguration(11, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        placeCard.addView(row)
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
            if (Convoy.active) { Convoy.setDestination(pl); openSheet("convoy") }
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
        dlg.window?.decorView?.let { dv -> L.translateTree(dv); dv.viewTreeObserver.addOnPreDrawListener { L.translateTree(dv); true } }
    }

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
        AlertDialog.Builder(this)
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
        Convoy.myPlace()?.let { (place, of) ->
            val nth = when (place) { 1 -> "1st 👑"; 2 -> "2nd"; 3 -> "3rd"; else -> "${place}th" }
            tripCard.addView(label("Convoy: $nth of $of", 15f, bold = true).apply { setTextColor(readable(accent)); gravity = Gravity.CENTER; setPadding(0, (6 * d).toInt(), 0, 0) })
        }
        tripCard.addView(View(this).apply { setBackgroundColor(dim(50)) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
            topMargin = (14 * d).toInt(); bottomMargin = (10 * d).toInt()
        })
        val speed = if (MapState.imperial) "${(trip.avgKmh * 0.621).toInt()} mph" else "${trip.avgKmh} km/h"
        val stats = listOf(
            NavEngine.formatDuration(trip.seconds.toDouble()) to "journey",
            NavEngine.formatDistance(trip.meters) to "travelled",
            (if (trip.savedSeconds >= 60) "${trip.savedSeconds / 60} min" else speed) to (if (trip.savedSeconds >= 60) "saved dodging traffic" else "average speed"),
            "+${trip.xp} XP" to (if (trip.landmarks > 0) "${trip.landmarks} new landmark" + (if (trip.landmarks > 1) "s" else "") else "experience"),
        )
        for (rowIdx in 0..1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0..1) {
                val (big, small) = stats[rowIdx * 2 + k]
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                    addView(label(big, 24f, bold = true).apply { setTextColor(ink); typeface = t.typeface })
                    addView(label(small, 13f).apply { setTextColor(dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
            tripCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // level bar
        val (have, of) = Explorer.progress()
        val up = Explorer.level > trip.levelBefore
        tripCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (12 * d).toInt(), 0, (6 * d).toInt())
            addView(label(if (up) "Level up! Level ${Explorer.level}" else "Explorer · level ${Explorer.level}", 14f, bold = true).apply {
                setTextColor(if (up) readable(accent) else ink)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("$have / $of XP", 14f).apply { setTextColor(dim()) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        tripCard.addView(object : View(this) {
            private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                val r = height / 2f
                pt.color = dim(50); c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, pt)
                pt.color = accent; c.drawRoundRect(0f, 0f, width * (have.toFloat() / maxOf(of, 1)), height.toFloat(), r, r, pt)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, (10 * d).toInt()))
        tripCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("🅿 Parking nearby") {
                NavEngine.dismissTrip(); showNearby("PARKING NEARBY", listOf("parking"))
            }.apply { setTextColor(ink); textSize = 15f; background = bg(Color.TRANSPARENT, dim(160), 1.5f, 14f) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.2f).apply { marginEnd = (10 * d).toInt() })
            addView(pill("Done") { NavEngine.dismissTrip() }.apply {
                setTextColor(onAccent(accent)); typeface = t.typeface; background = bg(accent, radius = 14f)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (18 * d).toInt() })
    }

    // ------------------------------------------------------------------ state → UI

    /** Colours everything to match the map theme and shows what fits the moment. */
    private var lastLookKey = ""
    private var tripLook = ""

    private var screenKept = false

    private fun refreshChrome() {
        // the screen stays on only while a route is being followed or chosen; otherwise the
        // phone dims and locks as usual (the biggest battery saver of all)
        val keep = NavEngine.active || NavEngine.planning
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
        val planning = NavEngine.planning
        val searching = search.hasFocus() || suggestScroll.visibility == View.VISIBLE || pickFor != null

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
        homeSheet.visibility = if (nav || planning || searching || placeShown != null || (Places.recents().isEmpty() && Places.savedPlaces().isEmpty() && Parked.get() == null && t != Theme.TRAILBLAZER)) View.GONE else View.VISIBLE
        if (chipsWas != chipsScroll.visibility || homeWas != homeSheet.visibility) lastHomeKey = ""
        if ((nav || planning) && suggestScroll.visibility == View.VISIBLE) closeSearch()
        val v = MapState.phone
        recenterBtn.visibility = if ((v.following && v.userBearing == null && !v.overview) || planning) View.GONE else View.VISIBLE
        muteBtn.visibility = if (nav) View.VISIBLE else View.GONE
        muteBtn.text = if (Voice.muted) "🔇" else "🔊"
        // while driving only three: sound, report, ☰ (style, traffic and overview live in ☰)
        overviewBtn.visibility = View.GONE
        styleBtn.visibility = if (nav) View.GONE else View.VISIBLE
        navMenuBtn.visibility = if (nav) View.VISIBLE else View.GONE
        reportBtn.visibility = if (planning) View.GONE else View.VISIBLE
        trafficBtn.visibility = if (Traffic.key.isEmpty() || nav) View.GONE else View.VISIBLE
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
        val homeKey = "${t.name}|${MapState.dark}|${MapState.imperial}|$etaVersion|${Explorer.version}|${Explorer.xp}|${Places.recents().joinToString { it.name }}|" +
            "${Places.saved("home")?.name}|${Places.saved("work")?.name}|${LocationTracker.position != null}|${Parked.get()?.at}|${Convoy.active}|${Convoy.members.size}|${Places.labelled().joinToString { it.label + it.icon }}"
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
            "offline" -> renderOffline()
            "convoy" -> if (Convoy.counting) closeSheet() else renderConvoy() // the countdown shows on the map
            "quick" -> renderQuick()
            "fuelfilters" -> {} // drawn once when opened, so its switches keep their scroll place
        }
        val trip = NavEngine.lastTrip
        tripOverlay.visibility = if (trip != null) View.VISIBLE else View.GONE
        if (trip != null && (trip !== shownTrip || lookKey != tripLook)) { shownTrip = trip; tripLook = lookKey; renderTrip(trip) }
        if (trip == null) shownTrip = null
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
        updateMapInsets()
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
            homeSheet.visibility == View.VISIBLE -> homeSheet.height
            else -> insets.bottom
        }
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
                renderResults(merged, when {
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
            row.animate().alpha(0f).translationX(row.width * 0.3f).setDuration(180).withEndAction { showRecents() }.start()
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

    private fun openSheet(kind: String) {
        if (kind != sheetKind) settingsPage = null
        quickKey = ""
        sheetKind = kind
        if (sheetOverlay.visibility != View.VISIBLE) {
            // the sheet slides up over a fading-in shade
            sheetOverlay.alpha = 0f; sheetOverlay.visibility = View.VISIBLE
            sheetOverlay.animate().alpha(1f).setDuration(160).start()
            sheetHost.translationY = 60 * d
            sheetHost.animate().translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
        }
        refreshChrome()
        if (kind == "fuelfilters") renderFuelFilters()
    }

    private fun closeSheet() {
        sheetOverlay.visibility = View.GONE
        sheetKind = ""
        sheetHost.removeAllViews()
    }

    private fun sheetHeader(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 22f, bold = true).apply { setTextColor(ink); typeface = theme.typeface }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill("✕") { closeSheet() }.apply { setTextColor(ink) })
    }

    /** Shows a card with a short slide up and fade in (only when it wasn't showing already). */
    private fun appear(v: View, from: Float = 36f) {
        if (v.visibility == View.VISIBLE) return
        v.visibility = View.VISIBLE
        v.alpha = 0f; v.translationY = from * d
        v.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }

    internal fun showStylePicker() = openSheet("style")

    private fun renderStylePicker() {
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
        // theme grid, 3 per row
        val dark = MapState.dark
        var row: LinearLayout? = null
        Theme.entries.forEachIndexed { i, th ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                sheetHost.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
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
        sheetHost.addView(settingRow("Android Auto style", MapState.carTheme?.title ?: "Same as phone") { chooseCarStyle() },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
    }

    private fun chooseCarStyle() {
        val names = arrayOf("Same as phone") + Theme.entries.map { it.title }.toTypedArray()
        val cur = MapState.carTheme?.let { it.ordinal + 1 } ?: 0
        AlertDialog.Builder(this).setTitle("Android Auto style")
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
            if (Traffic.key.isNotEmpty()) switchRow("Traffic on the map", Traffic.showLayer) { Traffic.showLayer = it } else null,
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
    internal fun openReportForTest() = openSheet("report")
    internal fun openSheetForTest(kind: String) = openSheet(kind)

    /** Which settings page is open: null = the list of groups. */
    private var settingsPage: String? = null

    private class SettingsGroup(val key: String, val title: String, val summary: String, val icon: Int, val tint: Int)

    private fun settingsGroups() = listOf(
        SettingsGroup("map", "Map", "Style, light or dark, Android Auto style, places, offline maps", R.drawable.ic_set_map, Color.rgb(59, 91, 219)),
        SettingsGroup("nav", "Navigation", "Voice, faster routes, tolls and motorways, live traffic", R.drawable.ic_set_nav, Color.rgb(30, 158, 90)),
        SettingsGroup("alerts", "Alerts", "Speed and red-light cameras, accidents, cameras you marked", R.drawable.ic_set_alert, Color.rgb(194, 65, 12)),
        SettingsGroup("sharing", "Sharing", "Your name, convoy, live arrival", R.drawable.ic_set_share, Color.rgb(124, 58, 237)),
        SettingsGroup("app", "App", "Trip history, explorer profile, privacy, about" + if (Updater.ENABLED) ", updates" else "", R.drawable.ic_set_app, Color.rgb(13, 110, 97)),
        SettingsGroup("advanced", "Advanced", "Star ratings and reviews, welcome screens", R.drawable.ic_set_adv, Color.rgb(100, 100, 110)),
    )

    private fun renderSettings() {
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 24f, topOnly = true)
        val maxH = minOf((resources.displayMetrics.heightPixels * 0.72f).toInt(), (640 * d).toInt())
        val page = settingsPage
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (page == null) {
            sheetHost.addView(sheetHeader("Settings"))
            for (g in settingsGroups()) body.addView(settingsEntry(g), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            body.addView(label("GameRoute build ${Updater.currentVersion(this)}", 12f).apply {
                setTextColor(dim(150)); gravity = Gravity.CENTER; setPadding(0, (14 * d).toInt(), 0, (4 * d).toInt())
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            sheetHost.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return
        }
        val g = settingsGroups().first { it.key == page }
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(pill("‹") { settingsPage = null; refreshChrome() }.apply { setTextColor(ink); textSize = 24f; contentDescription = "Back to settings" })
            addView(label(g.title, 22f, bold = true).apply { setTextColor(ink); typeface = theme.typeface }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("✕") { closeSheet() }.apply { setTextColor(ink) })
        })
        val voice = if (Voice.muted) "Off" else if (Voice.lang == Phrases.GREEK) "Ελληνικά" else "English"
        val rows: List<View> = when (page) {
            "map" -> listOf(
                settingRow("Map style", theme.title) { openSheet("style") },
                settingRow("Light / dark", MapState.lightMode.label) { chooseLightMode() },
                settingRow("Android Auto style", MapState.carTheme?.title ?: "Same as phone") { chooseCarStyle() },
                switchRow("Car: dark map when headlights are on", MapState.followHeadlights) { MapState.followHeadlights = it },
                switchRow("Places on the map (fuel, parking, food…)", MapState.showPlaces) { MapState.showPlaces = it },
                settingRow("Units", if (MapState.imperial) "Miles" else "Kilometres") { MapState.setImperial(!MapState.imperial) },
                settingRow("Offline maps", Offline.areas().let { a -> if (a.isEmpty()) "None yet" else "${a.size} area${if (a.size == 1) "" else "s"} · ${Offline.size(a.sumOf { it.bytes })}" }) { openSheet("offline") },
            )
            "nav" -> listOf(
                settingRow("Spoken directions", voice) { chooseVoice() },
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
                    AlertDialog.Builder(this).setTitle("Cameras you marked")
                        .setMessage(if (n == 0) "None yet. While driving, tap ⚠ › Speed camera or Red-light camera to mark one where you are."
                            else "$n camera${if (n == 1) "" else "s"} saved on this phone. GameRoute warns you before each one.")
                        .apply { if (n > 0) setNeutralButton("Delete all") { _, _ -> Alerts.clearMyCameras(); showSettings() } }
                        .setPositiveButton("OK", null).showT()
                },
            )
            "sharing" -> listOf(
                settingRow("Your name in convoys", Convoy.myName.ifBlank { "Not set" }) { editConvoyName { showSettings() } },
                settingRow("Convoy", if (Convoy.active) "On · ${Convoy.members.size + 1} drivers" else "Drive together") { openSheet("convoy") },
            )
            "app" -> listOfNotNull(
                settingRow("Trip history", "${Explorer.history().size} trips") { openSheet("trips") },
                settingRow("Explorer profile", "Level ${Explorer.level} · ${Explorer.xp} XP") { showProfile() },
                if (Updater.ENABLED) settingRow("Check for updates", "build ${Updater.currentVersion(this)}") { checkForUpdates(manual = true) } else null,
                settingRow("Language", L.label) { chooseLanguage() },
                settingRow("Privacy policy", null) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://vyto-line.github.io/GameRoute/privacy/"))) } catch (_: Exception) {}
                },
                settingRow("About & map credits", null) { showAbout() },
            )
            else -> listOf(
                settingRow("Star ratings & reviews", if (Reviews.enabled) "Google key set" else "Off: Reviews opens Google Maps") { chooseGoogleKey() },
                settingRow("Show the welcome screens again", null) { closeSheet(); showWelcome() },
            )
        }
        group("", *rows.toTypedArray()).forEach { body.addView(it) }
        sheetHost.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { height = WRAP_CONTENT })
        body.post { (body.parent as? View)?.let { sv -> if (body.height > maxH) { sv.layoutParams.height = maxH; sv.requestLayout() } } }
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
        AlertDialog.Builder(this).setTitle("Star ratings & reviews").setView(box)
            .setPositiveButton("Save") { _, _ -> Reviews.key = input.text.toString(); showSettings() }
            .setNeutralButton("Remove") { _, _ -> Reviews.key = ""; showSettings() }
            .setNegativeButton("Cancel", null)
            .showT()
    }

    private fun chooseLanguage() {
        val opts = arrayOf("Auto (phone's language)", "English", "Ελληνικά")
        val cur = when (L.mode) { "en" -> 1; "el" -> 2; else -> 0 }
        AlertDialog.Builder(this).setTitle("Language")
            .setSingleChoiceItems(opts, cur) { dlg, k ->
                dlg.dismiss()
                L.setMode(when (k) { 1 -> "en"; 2 -> "el"; else -> "auto" })
                recreate()
            }.showT()
    }

    private fun chooseVoice() {
        val opts = arrayOf("English", "Ελληνικά", "Off")
        val cur = if (Voice.muted) 2 else if (Voice.lang == Phrases.GREEK) 1 else 0
        AlertDialog.Builder(this).setTitle("Spoken directions")
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

    private fun chooseAutoSwitch() {
        val opts = arrayOf("Switch automatically", "Ask me (keep my route)")
        AlertDialog.Builder(this).setTitle("When a faster route is found")
            .setSingleChoiceItems(opts, if (NavEngine.autoSwitch) 0 else 1) { dlg, k ->
                NavEngine.autoSwitch = k == 0; dlg.dismiss(); refreshChrome()
            }.showT()
    }

    private fun chooseLightMode() {
        val modes = MapState.LightMode.entries
        AlertDialog.Builder(this)
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
        val u = i.data
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

    /** Change the name the convoy sees (Settings › Convoy, or the convoy screen). */
    private fun editConvoyName(then: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Your name"; setSingleLine(); setText(Convoy.myName); setSelection(text.length)
            filters = arrayOf(android.text.InputFilter.LengthFilter(16))
        }
        AlertDialog.Builder(this).setTitle("Your name in convoys").setMessage("Shown next to your arrow on the others' maps.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> Convoy.myName = input.text.toString().ifBlank { "Driver" }; convoyKey = ""; refreshChrome(); then?.invoke() }
            .setNegativeButton("Cancel", null).showT()
    }

    private fun withConvoyName(then: () -> Unit) {
        if (Convoy.myName.isNotBlank()) { then(); return }
        val input = EditText(this).apply { hint = "Your name"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Your name in convoys").setMessage("Shown next to your arrow on the others' maps.")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> Convoy.myName = input.text.toString().ifBlank { "Driver" }; then() }
            .setNegativeButton("Cancel", null).showT()
    }

    private fun startConvoyFromPreview() {
        val dest = NavEngine.planPlace ?: return
        withConvoyName {
            Convoy.create(dest)
            openSheet("convoy") // (Invite is there; no share sheet popping up by itself)
        }
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
            if (Convoy.join(link)) openSheet("convoy")
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
            val on = v.text == icon
            v.background = if (on) bg(Color.argb(60, Color.red(accent), Color.green(accent), Color.blue(accent)), accent, 2f, 20f) else null
        } }
        for (e in icons) iconRow.addView(TextView(this).apply {
            text = e; textSize = 20f; gravity = Gravity.CENTER
            setOnClickListener { icon = e; paintIcons() }
        }, LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f))
        paintIcons()
        box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(iconRow, LinearLayout.LayoutParams((icons.size * 42 * d).toInt(), WRAP_CONTENT)) })
        val dlg = AlertDialog.Builder(this)
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
        L.translateTree(box)
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
            AlertDialog.Builder(this)
                .setTitle("${p.from} removed the destination")
                .setMessage(L.t("The convoy now just shares where everyone is.") +
                    (if (mine != null) "\n\n" + L.t("You're driving to ${mine.name}: your route stays unless you end it.") else ""))
                .setPositiveButton("OK, remove") { _, _ -> Convoy.accept(); refreshChrome() }
                .setNegativeButton("Keep mine") { _, _ -> Convoy.decline() }
                .setCancelable(false)
                .showT()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${p.from} chose where the convoy goes")
            .setMessage(place.name + (if (place.address.isNotBlank()) "\n" + place.address else "") +
                (if (driving) "\n\n" + L.t("Your route will change to it.") else ""))
            .setPositiveButton(if (driving) "Go there" else "Accept") { _, _ -> Convoy.accept(); refreshChrome() }
            .setNegativeButton("Not now") { _, _ -> Convoy.decline() }
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
        AlertDialog.Builder(this).setMessage("Leave the convoy? The others will stop seeing you.")
            .setPositiveButton("Leave") { _, _ -> Convoy.leave(); if (sheetKind == "convoy" || sheetKind == "quick") closeSheet() }
            .setNegativeButton("Stay", null).showT()
    }

    private fun renderConvoy() {
        val rows = Convoy.rows(accent)
        val key = "${Convoy.code}|${Convoy.myName}|${Convoy.destination?.name}|${rows.joinToString { "${it.name}${it.kmh}${it.state}${it.eta / 60000}${(it.progress * 50).toInt()}${it.ready}${it.stale}" }}|${NavEngine.active}|${theme.name}|${MapState.dark}"
        if (key == convoyKey && sheetHost.childCount > 0) return
        convoyKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        val p = (12 * d).toInt()
        if (!Convoy.active) {
            sheetHost.addView(sheetHeader("Convoy"))
            // your name first: it's what the others see
            sheetHost.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(p, p / 3, p / 3, p / 3); background = bg(dim(22), radius = 14f)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("YOUR NAME", 11f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f })
                    addView(label(Convoy.myName.ifBlank { "Not set yet" }, 17f, bold = true).apply { setTextColor(ink) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(pill("Edit") { editConvoyName() }.apply { textSize = 14f; setTextColor(readable(accent)) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2 })
            sheetHost.addView(label("Drive together: everyone sees the others on the map with their speed and arrival time.", 14f).apply {
                setTextColor(dim()); setPadding(0, p, 0, p / 2)
            })
            sheetHost.addView(pill("＋  Create a convoy") { withConvoyName { Convoy.create(null); openSheet("convoy") } }.apply {
                textSize = 17f; setTextColor(onAccent(accent)); background = bg(accent, radius = 22f)
            }, LinearLayout.LayoutParams(MATCH_PARENT, (54 * d).toInt()))
            sheetHost.addView(label("You can choose where to go now or later, together.", 12f).apply {
                setTextColor(dim()); gravity = Gravity.CENTER; setPadding(0, p / 3, 0, p)
            })
            sheetHost.addView(label("JOIN WITH A CODE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding(p / 3, 0, 0, p / 2) })
            lateinit var joinBtn: TextView
            val input = EditText(this).apply {
                hint = "K7P 2QX"; setSingleLine(); setTextColor(ink); setHintTextColor(dim(110))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                textSize = 30f; letterSpacing = 0.18f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
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
            sheetHost.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            joinBtn = pill("Join") { joinConvoy(input.text.toString()) }.apply {
                setTextColor(onAccent(accent)); background = bg(accent, radius = 14f); alpha = 0.45f; isEnabled = false
            }
            sheetHost.addView(joinBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p })
            sheetHost.addView(pill("Paste an invite link") {
                val clip = getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                if (clip.isNullOrBlank()) Toast.makeText(this, L.t("Nothing to paste"), Toast.LENGTH_SHORT).show() else joinConvoy(clip)
            }.apply { textSize = 14f; setTextColor(readable(accent)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            input.requestFocus()
            return
        }
        val dest = Convoy.destination
        sheetHost.addView(sheetHeader("Convoy"))
        // the join code, big enough to read out to someone in the car
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(22), radius = 14f)
            addView(label("CODE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.08f; setPadding(0, 0, p, 0) })
            addView(label(Convoy.pretty() ?: "", 26f, bold = true).apply { setTextColor(ink); letterSpacing = 0.12f; typeface = Typeface.MONOSPACE },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(pill("Copy") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Convoy code", Convoy.pretty()))
                Toast.makeText(this@MainActivity, L.t("Code copied"), Toast.LENGTH_SHORT).show()
            }.apply { textSize = 14f; setTextColor(readable(accent)) })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2; bottomMargin = p / 2 })
        // where we're going: choose it (or change it) here; everyone gets it
        sheetHost.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(p / 3, p / 3, 0, p / 3)
            addView(label(if (dest == null) "No destination yet" else "🏁  ${dest.name}", 15f, bold = dest != null).apply {
                setTextColor(if (dest == null) dim() else ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (dest == null) addView(pill("📍 Choose") { closeSheet(); startPicking("convoy") }.apply {
                textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 18f)
            })
            else addView(pill("Change ▾") { showDestinationMenu(this) }.apply { textSize = 14f; setTextColor(readable(accent)) })
        })
        // progress line + one row per person
        sheetHost.addView(object : View(this) {
            override fun onDraw(c: Canvas) {
                val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                val y = height / 2f; val x0 = 10 * d; val x1 = width - 34 * d
                pnt.color = dim(70); c.drawRoundRect(x0, y - 1.5f * d, x1, y + 1.5f * d, 2 * d, 2 * d, pnt)
                pnt.textSize = 14 * d; c.drawText("🏁", x1 + 6 * d, y + 5 * d, pnt)
                for (r in rows.sortedBy { if (it.me) 1 else 0 }) {
                    pnt.color = if (r.stale) Color.GRAY else r.color
                    c.drawCircle(x0 + ((x1 - x0) * r.progress).toFloat(), y, 8 * d, pnt)
                }
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, (30 * d).toInt()))
        for (r in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, p / 2, 0, p / 2) }
            row.addView(View(this).apply { background = bg(if (r.stale) Color.GRAY else r.color, radius = 6f) }, LinearLayout.LayoutParams((11 * d).toInt(), (11 * d).toInt()).apply { marginEnd = p })
            row.addView(label("${if (r.me) "You" else r.name}  ", 16f, bold = true).apply { setTextColor(ink) })
            row.addView(label(Convoy.relative(r), 13f).apply { setTextColor(dim()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(label(if (r.state == "arrived") "✓" else "${r.kmh} km/h", 14f, bold = true).apply {
                setTextColor(if (r.kmh == 0 && r.state == "driving") readable(Color.rgb(230, 60, 60)) else ink); setPadding(p, 0, p, 0)
            })
            row.addView(label(if (r.eta > 0) NavEngine.clock(r.eta) else "–", 15f, bold = true).apply { setTextColor(readable(Color.rgb(47, 180, 100))) })
            row.isClickable = true
            row.setOnClickListener { showFriend(r.id) }
            sheetHost.addView(row)
        }
        if (Convoy.members.isEmpty()) sheetHost.addView(label("Nobody else yet: send the code or the invite link.", 14f).apply { setTextColor(dim()); setPadding(0, p / 2, 0, 0) })
        sheetHost.addView(label("The others see you as \"${Convoy.myName.ifBlank { "Driver" }}\"  ·  Change name", 13f, bold = true).apply {
            setTextColor(readable(accent)); setPadding(0, p / 2, 0, 0); setOnClickListener { editConvoyName() }
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, p, 0, 0) }
        buttons.addView(pill("🔗 Invite") { inviteToConvoy() }.apply { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 22f) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p })
        buttons.addView(pill("Leave") { confirmLeaveConvoy() }.apply { setTextColor(ink); background = bg(Color.TRANSPARENT, dim(120), 1.5f, 22f) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = p })
        val lobby = Convoy.lobby()
        val race = !NavEngine.active && dest != null && lobby.isNotEmpty()
        if (!NavEngine.active && dest != null && !race) buttons.addView(pill("Start") { closeSheet(); NavEngine.start(this, dest); MapState.recenter() }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 22f)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f))
        // on a route of my own: one tap to join the others' route instead
        if (NavEngine.active && dest != null && !Convoy.onConvoyRoute) sheetHost.addView(pill("➤  Join the convoy's route") {
            closeSheet(); NavEngine.start(this, dest); MapState.recenter()
        }.apply {
            setTextColor(onAccent(accent)); background = bg(accent, radius = 22f); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p })
        if (race) {
            // the race start: everyone presses Ready, then 3-2-1 and all go together
            val waiting = lobby.filter { !it.ready }.map { it.name }
            val ready = Convoy.meReady
            sheetHost.addView(pill(when {
                !ready -> "🏁 I'm ready"
                waiting.isEmpty() -> "✓ Everyone's ready!"
                else -> "✓ Ready · waiting for " + waiting.joinToString(", ")
            }) { Convoy.setReady(!ready) }.apply {
                textSize = 17f
                if (ready) { setTextColor(readable(Color.rgb(47, 180, 100))); background = bg(Color.TRANSPARENT, Color.rgb(47, 180, 100), 2f, 22f) }
                else { setTextColor(onAccent(accent)); background = bg(accent, radius = 22f) }
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(MATCH_PARENT, (54 * d).toInt()).apply { topMargin = p })
            sheetHost.addView(label(if (ready) "Tap again if you're not ready after all. When everyone is, a 3-2-1 countdown starts on every phone and the route starts by itself."
                else "When everyone has pressed it, a 3-2-1 countdown starts on every phone and the route starts by itself.", 13f).apply {
                setTextColor(dim()); setPadding(0, p / 2, 0, 0)
            })
        }
        sheetHost.addView(buttons)
        if (race) sheetHost.addView(label("Start without waiting", 13f, bold = true).apply {
            setTextColor(readable(accent)); gravity = Gravity.CENTER; setPadding(0, p, 0, 0)
            setOnClickListener { closeSheet(); NavEngine.start(this@MainActivity, dest!!); MapState.recenter() }
        })
        sheetHost.addView(label("Positions update every second while moving, end-to-end encrypted: only people with the code can see them.", 12f).apply {
            setTextColor(dim()); setPadding(0, p, 0, 0)
        })
    }

    private var offlineKey = ""
    private val offlineListener: () -> Unit = { runOnUiThread { if (sheetKind == "offline") { offlineKey = ""; renderOffline() } } }

    /** Offline maps: download the map around here, list and delete downloads. */
    private fun renderOffline() {
        val areas = Offline.areas()
        val key = "${areas.size}|${Offline.downloading}|${Offline.done.get() / 10}|${theme.name}|${MapState.dark}"
        if (key == offlineKey && sheetHost.childCount > 0) return
        offlineKey = key
        Offline.changed -= offlineListener; Offline.changed += offlineListener
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("Offline maps"))
        val p = (12 * d).toInt()
        sheetHost.addView(label("The map, street names and places of an area, kept on your phone: it shows without signal and uses no data. " +
            "Routes and search still need the internet.", 14f).apply { setTextColor(dim()); setPadding(0, p / 2, 0, p) })
        val dl = Offline.downloading
        if (dl != null) {
            sheetHost.addView(label("Downloading $dl… ${Offline.done.get()} / ${Offline.total}", 15f, bold = true).apply { setTextColor(readable(accent)) })
            sheetHost.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = Offline.total.coerceAtLeast(1); progress = Offline.done.get()
                progressTintList = android.content.res.ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p / 2; bottomMargin = p / 2 })
            sheetHost.addView(pill("Stop") { Offline.cancelled = true }.apply { setTextColor(ink) })
        } else {
            val center = map.center() ?: LocationTracker.position
            if (center != null) for ((km, title) in listOf(5 to "City centre", 15 to "Wider area")) {
                val n = Offline.tileCount(center, km)
                sheetHost.addView(pill("⬇  $title · ${km} km around the map's centre · about ${Offline.size(n * 110_000L)}") {
                    io.execute {
                        val name = try { Places.addressAt(center).let { a -> a.address.split(", ").firstOrNull()?.takeIf { it.isNotBlank() } ?: a.name } } catch (e: Exception) { null }
                            ?: "Area ${"%.2f".format(center.lat)}, ${"%.2f".format(center.lon)}"
                        Offline.download("$name ($km km)", center, km)
                    }
                }.apply {
                    textSize = 14f; setTextColor(onAccent(accent)); background = bg(accent, radius = 14f)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
            }
        }
        if (areas.isNotEmpty()) sheetHost.addView(label("ON THIS PHONE", 12f, bold = true).apply { setTextColor(dim()); letterSpacing = 0.06f; setPadding(0, p, 0, p / 3) })
        val date = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
        for (a in areas) {
            sheetHost.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(p, p / 2, p / 2, p / 2); background = bg(dim(22), radius = 14f)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(a.name, 16f, bold = true).apply { setTextColor(ink) })
                    addView(label("${Offline.size(a.bytes)} · downloaded ${date.format(java.util.Date(a.at))} · updates itself", 13f).apply { setTextColor(dim()) })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(pill("Delete") {
                    AlertDialog.Builder(this@MainActivity).setMessage("Delete the offline map of ${a.name}?")
                        .setPositiveButton("Delete") { _, _ -> Offline.delete(a) }.setNegativeButton("Cancel", null).showT()
                }.apply { textSize = 14f; setTextColor(readable(Color.rgb(220, 60, 60))) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = p / 2 })
        }
    }

    private var tripsKey = ""

    /** Past trips, newest first, with totals at the top. */
    private fun renderTrips() {
        val list = Explorer.history()
        val key = "${list.size}|${list.firstOrNull()?.arrivedAt}|${theme.name}|${MapState.dark}"
        if (key == tripsKey && sheetHost.childCount > 0) return
        tripsKey = key
        sheetHost.removeAllViews()
        sheetHost.background = bg(panel, radius = 22f, topOnly = true)
        sheetHost.addView(sheetHeader("Trip history"))
        val p = (12 * d).toInt()
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
            AlertDialog.Builder(this).setMessage("Delete all ${list.size} trips from the history? Your XP stays.")
                .setPositiveButton("Delete") { _, _ -> Explorer.clearHistory(); tripsKey = ""; refreshChrome() }
                .setNegativeButton("Cancel", null).showT()
        }.apply { setTextColor(dim()) })
        sheetHost.addView(ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(MATCH_PARENT, minOf((resources.displayMetrics.heightPixels * 0.62f).toInt(), (560 * d).toInt())))
    }

    private fun showProfile() {
        val (have, of) = Explorer.progress()
        AlertDialog.Builder(this)
            .setTitle("Explorer · level ${Explorer.level}")
            .setMessage("${Explorer.xp} XP in total ($have / $of to the next level)\n" +
                "${Explorer.foundCount} landmarks discovered\n\n" +
                "Every trip earns XP (more for longer drives and for taking faster routes). " +
                "Pass close to museums, monuments, viewpoints and other landmarks to discover them: " +
                "+25 XP each. In the Catch 'Em Trail style they show on the map as blue ? markers.")
            .setPositiveButton("OK", null).showT()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("GameRoute build ${Updater.currentVersion(this)}")
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
                    if (manual) Toast.makeText(this, L.t("You have the latest version (build ${Updater.currentVersion(this)})"), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Update available")
                    .setMessage("${r.tag} is ready (you have build ${Updater.currentVersion(this)}). Download and install it?")
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
                AlertDialog.Builder(this)
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
        Parked.changed += stateListener
        Alerts.changed += stateListener
        Convoy.changed += stateListener
        refreshChrome()
    }

    override fun onStop() {
        NavEngine.changed -= stateListener
        MapState.changed -= stateListener
        Voice.changed -= stateListener
        Explorer.changed -= stateListener
        Parked.changed -= stateListener
        Alerts.changed -= stateListener
        Convoy.changed -= stateListener
        main.removeCallbacks(ticker)
        // in the background and not navigating: let the GPS rest
        LocationTracker.release(this, "phone")
        if (!LocationTracker.inUse && !NavEngine.active) Voice.shutdown()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 200_000) }
    }

    override fun onPause() {
        getSystemService(SensorManager::class.java).unregisterListener(this)
        LocationTracker.setCompass(null)
        super.onPause()
    }

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
        if (abs(Geo.angleDiff(lastCompass, deg)) > 2f) {
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
    private val area = Rect()
    var insetTop = 0
    var insetBottom = 0
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
        area.set(0, insetTop, width, height - insetBottom)
        renderer.draw(canvas, width, height, area, resources.displayMetrics.density, hud = true)
        // full frame rate under the fingers; 30 fps is plenty for the gliding arrow and zooms
        // (a 120 Hz phone would otherwise draw the whole map 120 times a second while driving)
        if (renderer.animating) {
            // slow or standing: 20 fps is enough and saves battery; 30 fps when really moving
            if (MapState.gesture) postInvalidateOnAnimation()
            else postInvalidateDelayed(if (LocationTracker.speedMps > 8f) 33 else 50)
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
