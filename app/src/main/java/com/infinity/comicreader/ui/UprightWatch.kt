package com.infinity.comicreader.ui

import android.content.Context
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.OrientationEventListener
import kotlin.math.abs

/**
 * Settles which way is up from [OrientationEventListener] readings: a quarter (0 upright,
 * 1 to 3 each a quarter turn on) counts once readings stay within [TOLERANCE] degrees of
 * it for [settleMs]. Lying flat, or tilted between quarters, nothing settles.
 */
class UprightFilter(private val settleMs: Long = SETTLE_MS) {

    /** The quarter the latest readings point to, or -1. */
    var quarter = -1
        private set

    private var since = 0L

    /** Takes a reading of [degrees] (or [OrientationEventListener.ORIENTATION_UNKNOWN]) at [now]: true when it starts a quarter. */
    fun feed(degrees: Int, now: Long): Boolean {
        val q = quarterOf(degrees)
        if (q == quarter) return false
        quarter = q
        since = now
        return q >= 0
    }

    /** True when a quarter has held for [settleMs] at [now]. */
    fun settled(now: Long) = quarter >= 0 && now - since >= settleMs

    /** Forgets the readings. */
    fun reset() {
        quarter = -1
    }

    companion object {
        /** How long readings stay in one quarter before it counts: past a turn of the hand, still at once to the eye. */
        const val SETTLE_MS = 400L

        /** How far from a quarter a reading still points to it, in degrees. */
        const val TOLERANCE = 30

        /** The quarter [degrees] points to, or -1 when unknown or between quarters. */
        fun quarterOf(degrees: Int): Int {
            if (degrees < 0) return -1
            val d = degrees % 360
            val off = abs((d + 45) % 90 - 45)
            return if (off <= TOLERANCE) ((d + 45) / 90) % 4 else -1
        }
    }
}

/**
 * Watches the accelerometer through [OrientationEventListener] while [enabled], and calls
 * [onSettled] on the main thread once the way up settles ([UprightFilter]). Off, it reads
 * nothing; without the sensor it is never [unsure].
 */
class UprightWatch(context: Context, private val onSettled: () -> Unit) {

    private val filter = UprightFilter()
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { if (filter.settled(SystemClock.uptimeMillis())) onSettled() }

    private val listener = object : OrientationEventListener(context.applicationContext, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(orientation: Int) {
            if (filter.feed(orientation, SystemClock.uptimeMillis())) {
                handler.removeCallbacks(check)
                handler.postDelayed(check, UprightFilter.SETTLE_MS)
            } else if (filter.quarter < 0) {
                handler.removeCallbacks(check)
            }
        }
    }

    /** True while readings flow; set only where the sensor exists. */
    var enabled = false
        set(value) {
            val on = value && listener.canDetectOrientation()
            if (on == field) return
            field = on
            if (on) {
                listener.enable()
            } else {
                listener.disable()
                handler.removeCallbacks(check)
                filter.reset()
            }
        }

    /** True when the sensor cannot tell the way up now: lying flat, or not settled yet. */
    fun unsure(): Boolean = listener.canDetectOrientation() && !filter.settled(SystemClock.uptimeMillis())
}
