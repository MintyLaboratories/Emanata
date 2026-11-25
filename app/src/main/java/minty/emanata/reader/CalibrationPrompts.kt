package minty.emanata.reader

import org.json.JSONObject
import kotlin.math.ceil

/** How the app offers calibration as the library shows ([CalibrationPrompts.offer]). */
enum class CalibrationOffer {
    /** Nothing to offer. */
    NONE,

    /** The first calibration: the calibration screen opens on its own. */
    SCREEN,

    /** A later one: a banner in the library offers it. */
    BANNER
}

/** What stops a calibration the user starts, or lets it go ([CalibrationPrompts.gate]). */
enum class CalibrationGate {
    GO,

    /** Battery saver is on: ask whether to switch it off or to measure anyway. */
    SAVER,

    /** Too warm to measure fairly. */
    HOT,

    /** Too little battery left to spend on measuring. */
    LOW_BATTERY
}

/** Where a calibration run is: waiting for a fair moment and preparing its pages, or timing [candidate] in [round] (from 1), [done] of [total] samples made. */
data class CalibrationProgress(
    val timing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val candidate: Candidate? = null,
    val round: Int = 0
)

/** What a run the user starts is gated on ([CalibrationPrompts.gate]): battery saver, heat as a thermal status ([Calibration.heatStatus]), battery left (null: unknown). */
data class DeviceReadings(val saverOn: Boolean, val thermalStatus: Int, val batteryPercent: Int?)

/** How a calibration run ended: [ok], or else [why] not; [canceled] when the user stopped it. */
data class CalibrationEnd(val ok: Boolean, val why: String?, val canceled: Boolean = false) {
    /** True when the device's heat ended or kept off the run. */
    val hot: Boolean get() = why?.contains(Calibration.TOO_WARM) == true

    /** True when the run stopped only because the app was left or a book opened: it goes on once back. */
    val away: Boolean get() = why == Calibration.AWAY || why == Calibration.READING
}

/** What the calibration screen and the library's banner drive: [Calibrator] on the device. */
interface CalibrationControl {
    /** True once what is kept has been read. */
    val loaded: Boolean

    /** True while a run is in progress. */
    val running: Boolean

    /** True while the user is stopping the run in progress ([cancel]): a [start] then follows it. */
    val canceling: Boolean

    /** True when a calibration is kept, from any system build or version: there is an old one to keep. */
    val calibrated: Boolean

    val progress: CalibrationProgress

    /** How the last run ended; null before any. */
    val lastEnd: CalibrationEnd?

    /** What to offer now, with battery saver [saverOn] ([CalibrationPrompts.offer]). */
    fun offer(saverOn: Boolean): CalibrationOffer

    /** About how long a run takes, in seconds ([CalibrationPrompts.estimateSeconds]). */
    fun estimateSeconds(): Int

    /** Starts a run the user asked for: no charger needed; under battery saver only with [underSaver]. Nothing when one is running, unless it is being canceled: then it starts once that one ends. */
    fun start(underSaver: Boolean)

    /** Stops the run in progress; what is in effect stays. */
    fun cancel()

    /** Lets the run go on unseen: while the app is in front and idle, starting again when back after the reader or another app stopped it. */
    fun background()

    /** Keeps the calibration in effect until the system build changes or the user recalibrates ([start]); nothing when no run is due. */
    fun skip()

    /** Offers nothing more until the app starts again. */
    fun decline()

    /** Notes that the screen opened on its own: once per launch. */
    fun shown()

    /** [listener] hears every change of the above, on any thread. */
    fun listen(listener: () -> Unit)

    fun unlisten(listener: () -> Unit)
}

/** The rules for offering calibration and starting it, kept free of the device so they can be tested. */
object CalibrationPrompts {
    /** Battery below this share, in percent, blocks a run ([gate]). */
    const val LOW_BATTERY_PERCENT = 20

    /**
     * What to offer: nothing until what is kept is [loaded], where calibration cannot run
     * ([canRun]), while a run is [running], or once the user [declined] this launch; the
     * screen for the very first calibration ([kept] none), once a launch ([shown]); else a
     * banner when one is [due][Calibration.due] for [fingerprint] and not skipped for it
     * ([skippedFor]), unless all that is due is replacing one made under battery saver
     * while it is still on ([saverOn]).
     */
    fun offer(
        loaded: Boolean,
        canRun: Boolean,
        running: Boolean,
        declined: Boolean,
        shown: Boolean,
        kept: JSONObject?,
        fingerprint: String?,
        skippedFor: String?,
        saverOn: Boolean
    ): CalibrationOffer = when {
        !loaded || !canRun || running || declined || fingerprint == null -> CalibrationOffer.NONE
        kept == null -> if (shown) CalibrationOffer.NONE else CalibrationOffer.SCREEN
        !Calibration.due(kept, fingerprint) || skippedFor == fingerprint -> CalibrationOffer.NONE
        saverOn && kept.optString("fingerprint") == fingerprint -> CalibrationOffer.NONE
        else -> CalibrationOffer.BANNER
    }

    /** What [CalibrationControl.skip] keeps the calibration for: [fingerprint] when a run is [due][Calibration.due] for it, else null (nothing to skip). */
    fun skipFor(kept: JSONObject?, fingerprint: String?): String? = fingerprint?.takeIf { Calibration.due(kept, it) }

    /**
     * Whether a run the user starts may go: heat ([thermalStatus] at [Calibration.HOT] or
     * above) and battery under [LOW_BATTERY_PERCENT] ([batteryPercent], null: unknown) block
     * it; battery saver asks first unless already [saverAccepted].
     */
    fun gate(saverOn: Boolean, thermalStatus: Int, batteryPercent: Int?, saverAccepted: Boolean): CalibrationGate = when {
        thermalStatus >= Calibration.HOT -> CalibrationGate.HOT
        batteryPercent != null && batteryPercent < LOW_BATTERY_PERCENT -> CalibrationGate.LOW_BATTERY
        saverOn && !saverAccepted -> CalibrationGate.SAVER
        else -> CalibrationGate.GO
    }

    /**
     * About how long a run takes, in whole seconds rounded up to 5, from the last one kept, else
     * [DEFAULT_SECONDS]: its timing without the waits for a fair moment and the samples made
     * again, plus preparing its pages and settling at most [Calibration.SETTLE_MS]. Waits for
     * heat or a busy app are the device's state then, not the run's length.
     */
    fun estimateSeconds(kept: JSONObject?): Int {
        val took = kept?.optLong("tookMs", 0L)?.takeIf { it > 0 } ?: return DEFAULT_SECONDS
        val timing = (took - kept.optLong("timingWaitMs") - kept.optLong("redoneMs")).coerceAtLeast(0)
        val ms = timing + kept.optLong("prepMs") + kept.optLong("settleMs").coerceIn(0, Calibration.SETTLE_MS)
        return (ceil(ms / 5000.0).toInt() * 5).coerceAtLeast(5)
    }

    /** The estimate before any run: about what the reference tablet takes. */
    const val DEFAULT_SECONDS = 40
}
