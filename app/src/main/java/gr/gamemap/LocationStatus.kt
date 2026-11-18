package gr.gamemap

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Is location usable: on, and GameRoute allowed to use it. When it isn't, the phone shows a card
 * (Turn on / Allow), the car says so on the map, and — only when it matters and GameRoute isn't on
 * screen (a convoy or a shared ETA running, or Android Auto connected) — one notification.
 */
object LocationStatus {
    enum class State { OK, OFF, NO_PERMISSION }

    @Volatile var state = State.OK
        private set
    val changed = Signal()
    private var app: Context? = null
    private const val CHANNEL = "location"
    private const val NOTE_ID = 7301
    @Volatile private var notified = false
    /** The phone app is on screen (then the card says it, no notification). */
    @Volatile var phoneVisible = false
    @Volatile var carConnected = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        check()
        try {
            ContextCompat.registerReceiver(ctx.applicationContext, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { check() }
            }, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Exception) {}
    }

    /** Look again (on start, when the switch changes, after the permission answer). */
    fun check() {
        val c = app ?: return
        if (Build.FINGERPRINT == "robolectric") return // screenshot tests: their state is set by hand
        val allowed = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val on = try {
            val lm = c.getSystemService(LocationManager::class.java)
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { true }
        val now = when { !on -> State.OFF; !allowed -> State.NO_PERMISSION; else -> State.OK }
        if (now == state) return
        state = now
        CrashLog.note("location: $now")
        if (now == State.OK) { cancelNote(); if (LocationTracker.hasPermission(c)) try { LocationTracker.start(c) } catch (_: Exception) {} }
        else maybeNotify("switch")
        changed.fire()
    }

    /** Android Auto connected (or went): tell once if location is off. */
    fun onCar(connected: Boolean) {
        carConnected = connected
        if (connected) { notified = false; check(); if (state != State.OK) maybeNotify("car") }
    }

    private fun maybeNotify(why: String) {
        val c = app ?: return
        if (state == State.OK || notified || phoneVisible) return
        val matters = carConnected || Convoy.active || LiveShare.active
        if (!matters) return
        notified = true
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t("Location"), NotificationManager.IMPORTANCE_HIGH))
            val open = if (state == State.OFF) Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + c.packageName))
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(c, 7301, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val title = if (state == State.OFF) L.t("Location is off") else L.t("GameRoute can't use your location")
            val text = when {
                Convoy.active -> L.t("Your convoy can't see you. Turn location on to keep sharing.")
                LiveShare.active -> L.t("Your shared arrival time has stopped. Turn location on to keep sharing.")
                else -> L.t("Android Auto connected: turn location on so GameRoute can guide you.")
            }
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi).setAutoCancel(true)
                .addAction(0, if (state == State.OFF) L.t("Turn on") else L.t("Allow"), pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH).build()
            nm.notify(NOTE_ID, n)
        } catch (e: Exception) { CrashLog.note("location note: $e") }
    }

    private fun cancelNote() {
        notified = false
        try { app?.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID) } catch (_: Exception) {}
    }

    internal fun setForTest(s: State) { state = s; changed.fire() }
}
