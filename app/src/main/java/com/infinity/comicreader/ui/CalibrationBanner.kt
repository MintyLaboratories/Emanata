package com.infinity.comicreader.ui

import android.app.Activity
import android.view.View
import android.widget.TextView
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.CalibrationControl
import com.infinity.comicreader.reader.CalibrationOffer
import com.infinity.comicreader.reader.DeviceReadings

/**
 * Calibration as the library offers it ([CalibrationControl.offer]): the calibration
 * screen opens on its own for the very first one, once the library shows ([ready]); a
 * later one gets a [banner] that keeps out of the way: Start opens the screen, Later hides
 * it until the next launch, Skip keeps the old calibration until the system build changes.
 */
class CalibrationBanner(
    private val activity: Activity,
    private val banner: View,
    private val ready: () -> Boolean
) {
    private val control: CalibrationControl get() = CalibrationScreenActivity.control
    private val text: TextView = banner.findViewById(R.id.calibration_banner_text)

    private val listener: () -> Unit = { activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) update() } }

    init {
        banner.findViewById<View>(R.id.calibration_banner_start).setOnClickListener {
            banner.visibility = View.GONE
            activity.startActivity(CalibrationScreenActivity.intent(activity))
        }
        banner.findViewById<View>(R.id.calibration_banner_later).setOnClickListener {
            control.decline()
            update()
        }
        banner.findViewById<View>(R.id.calibration_banner_skip).setOnClickListener {
            control.skip()
            update()
        }
    }

    /** Hears calibration's changes while the library is started. */
    fun start() = control.listen(listener)

    fun stop() = control.unlisten(listener)

    /** Shows what is on offer now: the screen when the library is [ready] for it, or the banner. */
    fun update() {
        // Progress of a run in the background comes often; nothing is on offer meanwhile.
        if (control.running) {
            banner.visibility = View.GONE
            return
        }
        val offer = control.offer(readings().saverOn)
        if (offer == CalibrationOffer.SCREEN && ready()) {
            control.shown()
            activity.startActivity(CalibrationScreenActivity.intent(activity))
        }
        if (offer == CalibrationOffer.BANNER) text.text = activity.getString(R.string.calibration_banner, control.estimateSeconds())
        banner.visibility = if (offer == CalibrationOffer.BANNER) View.VISIBLE else View.GONE
    }

    private fun readings(): DeviceReadings = CalibrationScreenActivity.readings(activity)
}
