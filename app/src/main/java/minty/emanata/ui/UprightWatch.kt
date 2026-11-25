package minty.emanata.ui

import android.content.Context
import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Settles which way is up from gravity readings ([degreesOf]): a quarter (0 upright,
 * 1 to 3 each a quarter turn clockwise on) counts once readings stay within [TOLERANCE]
 * degrees of it for [settleMs]. Lying flat, tilted past where the system turns the
 * screen, or tilted between quarters, nothing settles.
 */
class UprightFilter(private val settleMs: Long = SETTLE_MS) {

    /** The quarter the latest readings point to, or -1. */
    var quarter = -1
        private set

    private var since = 0L

    /** Takes a reading of [degrees] (or [UNKNOWN]) at [now]: true when it starts a quarter. */
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

        /** A reading that tells no way up. */
        const val UNKNOWN = -1

        /** How far gravity's strength may stray, in m/s², before a reading counts as the device moving. */
        private const val ACCELERATION_TOLERANCE = 4f

        /**
         * How far the screen may face down (first) and up (second) from upright, in degrees,
         * for the system to turn to display rotation `ROTATION_0` to `_270` in turn: the
         * platform's defaults for `config_autoRotationTiltTolerance`.
         */
        val TILT_LIMITS = intArrayOf(-25, 70, -25, 65, -25, 60, -25, 65)

        /** The quarter [degrees] points to, or -1 when unknown or between quarters. */
        fun quarterOf(degrees: Int): Int {
            if (degrees < 0) return -1
            val d = degrees % 360
            val off = abs((d + 45) % 90 - 45)
            return if (off <= TOLERANCE) ((d + 45) / 90) % 4 else -1
        }

        /**
         * The way up of an accelerometer reading [x], [y], [z] (m/s², along the device's
         * axes), in degrees clockwise from upright, or [UNKNOWN] when the device moves or
         * is tilted past [tiltLimits] (as [TILT_LIMITS]) for the quarter it points to.
         */
        fun degreesOf(x: Float, y: Float, z: Float, tiltLimits: IntArray = TILT_LIMITS): Int {
            val magnitude = sqrt(x * x + y * y + z * z)
            if (abs(magnitude - SensorManager.STANDARD_GRAVITY) > ACCELERATION_TOLERANCE) return UNKNOWN
            val degrees = Math.floorMod(90 - Math.toDegrees(atan2(y, -x).toDouble()).roundToInt(), 360)
            val q = quarterOf(degrees)
            if (q < 0) return degrees
            val tilt = Math.toDegrees(asin((z / magnitude).coerceIn(-1f, 1f).toDouble())).roundToInt()
            // A quarter turn clockwise is display rotation ROTATION_270.
            val rotation = (4 - q) % 4
            return if (tilt in tiltLimits[2 * rotation]..tiltLimits[2 * rotation + 1]) degrees else UNKNOWN
        }

        /** The device's own tilt limits ([TILT_LIMITS] unless it sets others). */
        fun systemTiltLimits(): IntArray {
            val res = Resources.getSystem()
            @android.annotation.SuppressLint("DiscouragedApi")
            val id = res.getIdentifier("config_autoRotationTiltTolerance", "array", "android")
            val own = if (id == 0) null else runCatching { res.getIntArray(id) }.getOrNull()
            return if (own != null && own.size == TILT_LIMITS.size) own else TILT_LIMITS
        }
    }
}

/**
 * Reads the accelerometer while [enabled], and calls [onSettled] on the main thread once
 * the way up settles ([UprightFilter]). Off, it reads nothing; without the sensor it is
 * never [unsure].
 */
class UprightWatch(context: Context, private val onSettled: () -> Unit) {

    private val sensors = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val tiltLimits = UprightFilter.systemTiltLimits()
    private val filter = UprightFilter()
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { if (filter.settled(SystemClock.uptimeMillis())) onSettled() }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            if (v.size < 3) return
            if (filter.feed(UprightFilter.degreesOf(v[0], v[1], v[2], tiltLimits), SystemClock.uptimeMillis())) {
                handler.removeCallbacks(check)
                handler.postDelayed(check, UprightFilter.SETTLE_MS)
            } else if (filter.quarter < 0) {
                handler.removeCallbacks(check)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** True while readings flow; set only where the sensor exists. Each time on, readings start afresh. */
    var enabled = false
        set(value) {
            val on = value && sensor != null
            if (on == field) return
            field = on
            if (on) {
                sensors!!.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                sensors?.unregisterListener(listener)
                handler.removeCallbacks(check)
                filter.reset()
            }
        }

    /** True when the sensor cannot tell the way up now: lying flat, tilted far back, or not settled yet. */
    fun unsure(): Boolean = sensor != null && !filter.settled(SystemClock.uptimeMillis())
}
