package gr.gamemap

import android.location.Location

/**
 * What the car itself tells Android Auto, when it shares it: its speed (from the wheels, so right
 * in tunnels and in a jam) and, in some cars, its own GPS, which has a better antenna than a phone
 * in a cup holder. Nothing here is required: a car that shares nothing changes nothing.
 */
object CarFeed {
    @Volatile private var speedMps = -1f
    @Volatile private var speedAt = 0L

    /** The car's speed in m/s if it told us in the last 3 seconds, else null. */
    fun speed(): Float? = speedMps.takeIf { it >= 0f && System.currentTimeMillis() - speedAt < 3_000 }

    fun onSpeed(mps: Float) {
        if (mps.isNaN() || mps < 0f || mps > 90f) return
        speedMps = mps; speedAt = System.currentTimeMillis()
    }

    /** A fix from the car's GPS: used only while the phone's GPS has gone quiet (LocationTracker decides). */
    fun onLocation(l: Location) {
        if (!l.hasAccuracy() || l.accuracy > 50f) return
        val fix = Location(LocationTracker.CAR).apply {
            latitude = l.latitude; longitude = l.longitude; accuracy = l.accuracy
            time = System.currentTimeMillis()
            if (l.hasBearing()) bearing = l.bearing
            val v = speed() ?: (if (l.hasSpeed()) l.speed else null)
            if (v != null) speed = v
        }
        LocationTracker.onLocationChanged(fix)
    }

    internal fun clear() { speedMps = -1f; speedAt = 0L }
}
