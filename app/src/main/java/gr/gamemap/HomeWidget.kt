package gr.gamemap

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews

/**
 * The home screen widget: your level ring, then Home and Work with the last drive time the app
 * worked out (fresh whenever the app was open in the last few minutes). Tap Home or Work: the app
 * opens with the routes there; tap the ring: the app.
 */
class HomeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, views(ctx))
    }

    companion object {
        /** Redraws every GameRoute widget (the app calls this when it goes to the background). */
        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, HomeWidget::class.java))
                if (ids.isEmpty()) return
                val v = views(ctx)
                for (id in ids) mgr.updateAppWidget(id, v)
            } catch (_: Exception) {}
        }

        internal fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_home)
            fun open(key: String?, code: Int): PendingIntent {
                val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (key != null) i.putExtra("widget_go", key)
                return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            for ((key, title, sub, box, code) in listOf(
                arrayOf<Any>("home", R.id.w_home_t, R.id.w_home_s, R.id.w_home, 1),
                arrayOf<Any>("work", R.id.w_work_t, R.id.w_work_s, R.id.w_work, 2),
            ).map { listOf(it[0], it[1], it[2], it[3], it[4]) }) {
                val k = key as String
                v.setTextViewText(title as Int, L.t(if (k == "home") "Home" else "Work"))
                val pl = try { Places.saved(k) } catch (_: Exception) { null }
                val eta = pl?.let { try { Places.cachedEta(it) } catch (_: Exception) { null } }
                v.setTextViewText(sub as Int, when {
                    pl == null -> L.t("Set in the app")
                    eta != null -> "${(eta.seconds + 30) / 60} min"
                    else -> L.t("Tap for routes")
                })
                v.setOnClickPendingIntent(box as Int, open(k, code as Int))
            }
            v.setImageViewBitmap(R.id.w_ring, ring(ctx))
            v.setOnClickPendingIntent(R.id.w_ring, open(null, 3))
            return v
        }

        /** The level ring: progress to the next level around your level number. */
        internal fun ring(ctx: Context): Bitmap {
            val d = ctx.resources.displayMetrics.density
            val s = (48 * d).toInt()
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val (have, of) = try { Explorer.progress() } catch (_: Exception) { 0 to 1 }
            val lv = try { Explorer.level } catch (_: Exception) { 1 }
            val col = try { Xp.rankColor().takeIf { it != 0 } ?: Color.rgb(255, 176, 40) } catch (_: Exception) { Color.rgb(255, 176, 40) }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND }
            val r = RectF(4 * d, 4 * d, s - 4 * d, s - 4 * d)
            p.color = Color.argb(70, 255, 255, 255); c.drawArc(r, 0f, 360f, false, p)
            p.color = col; c.drawArc(r, -90f, 360f * have / maxOf(of, 1), false, p)
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 17 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            c.drawText("$lv", s / 2f, s / 2f + 6 * d, t)
            return b
        }
    }
}
