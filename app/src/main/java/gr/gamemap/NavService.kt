package gr.gamemap

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Keeps GPS running while navigating, or while in a convoy, even with the phone screen off or
 * the app closed (a convoy keeps sharing where you are until you leave it).
 */
class NavService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val LEAVE = "gr.gamemap.LEAVE_CONVOY"

        /** Runs while navigating or in a convoy; its notification says which. */
        fun update(ctx: android.content.Context) {
            val c = ctx.applicationContext
            if (NavEngine.active || Convoy.active) {
                try { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, NavService::class.java)) } catch (_: Exception) {}
            } else c.stopService(Intent(c, NavService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == LEAVE) { Convoy.leave(); update(this); if (!NavEngine.active) { stopSelf(); return START_NOT_STICKY } }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("nav", "Navigation", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, "nav")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
        if (NavEngine.active) b.setContentTitle(L.t("GameRoute: mission active")).setContentText(L.t("Navigating to ${NavEngine.destinationName}"))
        else b.setContentTitle(L.t("Sharing your location with the convoy"))
            .setContentText(L.t("${Convoy.members.size + 1} drivers · until you leave"))
        if (Convoy.active) b.addAction(0, L.t("Leave convoy"),
            PendingIntent.getService(this, 8, Intent(this, NavService::class.java).setAction(LEAVE), PendingIntent.FLAG_IMMUTABLE))
        val n = b.build()
        try {
            ServiceCompat.startForeground(
                this, 7, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
            LocationTracker.start(this, "nav")
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        LocationTracker.release(this, "nav")
        super.onDestroy()
    }
}
