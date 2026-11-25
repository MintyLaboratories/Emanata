package minty.emanata.testing

import minty.emanata.reader.CalibrationControl
import minty.emanata.reader.CalibrationEnd
import minty.emanata.reader.CalibrationOffer
import minty.emanata.reader.CalibrationProgress

/** A calibration that runs only as the test says, noting what the screen and banner asked of it. */
class FakeCalibration(
    override var calibrated: Boolean = false,
    var offered: CalibrationOffer = CalibrationOffer.NONE
) : CalibrationControl {
    override var loaded: Boolean = true
    override var running: Boolean = false
    override var canceling: Boolean = false
    override var progress: CalibrationProgress = CalibrationProgress()
    override var lastEnd: CalibrationEnd? = null

    val starts = ArrayList<Boolean>()
    var canceled = 0
    var backgrounded = 0
    var skipped = 0
    var declined = 0
    var shownTimes = 0
    var saverAsked: Boolean? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    override fun offer(saverOn: Boolean): CalibrationOffer {
        saverAsked = saverOn
        return if (declined > 0 || (offered == CalibrationOffer.SCREEN && shownTimes > 0)) CalibrationOffer.NONE else offered
    }

    override fun estimateSeconds(): Int = 25

    override fun start(underSaver: Boolean) {
        if (running && !canceling) return
        canceling = false
        starts += underSaver
        running = true
        lastEnd = null
        tell()
    }

    override fun cancel() {
        canceled++
    }

    override fun background() {
        backgrounded++
    }

    override fun skip() {
        skipped++
        offered = CalibrationOffer.NONE
    }

    override fun decline() {
        declined++
    }

    override fun shown() {
        shownTimes++
    }

    override fun listen(listener: () -> Unit) {
        listeners += listener
    }

    override fun unlisten(listener: () -> Unit) {
        listeners -= listener
    }

    val listening: Int get() = listeners.size

    /** The run moved on to [p]. */
    fun move(p: CalibrationProgress) {
        progress = p
        tell()
    }

    /** The run ended as [end]. */
    fun end(end: CalibrationEnd) {
        running = false
        lastEnd = end
        tell()
    }

    private fun tell() = listeners.toList().forEach { it() }
}
