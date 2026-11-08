package com.infinity.comicreader.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.infinity.comicreader.R
import com.infinity.comicreader.reader.Calibration
import com.infinity.comicreader.reader.CalibrationControl
import com.infinity.comicreader.reader.CalibrationGate
import com.infinity.comicreader.reader.CalibrationPrompts
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.DeviceReadings

/**
 * The calibration screen: while calibration runs, the app stays behind it, the screen kept
 * on, with the Panel Tuner page ([PanelTunerView]), what is being timed and the choices:
 * Cancel (this run only: what is in effect stays, and the offer comes again at the next
 * launch), Keep old calibration (until the system build changes or the user recalibrates;
 * only when there is an old one) and Continue in background. Before a run, heat and a low
 * battery stop it with a plain word ([CalibrationPrompts.gate]); battery saver asks whether
 * to switch it off (the run starts once it is off) or to measure anyway. A run the reader or
 * another app stopped starts again when this screen is back; one that failed says so.
 */
class CalibrationScreenActivity : AppCompatActivity() {
    enum class State { RUNNING, SAVER, HOT, LOW_BATTERY, FAILED }

    var state: State? = null
        private set

    private val control get() = Companion.control

    /** True once the user chose to measure under battery saver. */
    private var saverAccepted = false

    /** True once this screen started a run or found one running. */
    private var started = false

    private lateinit var page: PanelTunerView
    private lateinit var title: TextView
    private lateinit var text: TextView
    private lateinit var progressBox: View
    private lateinit var bar: LinearProgressIndicator
    private lateinit var step: TextView
    private lateinit var round: TextView
    private lateinit var cancel: View
    private lateinit var keep: View
    private lateinit var anyway: View
    private lateinit var background: View
    private lateinit var saverSettings: View
    private lateinit var close: View

    /** The page holds still for a bias check (debuggable builds: `--ez still true`). */
    private var still = false

    private val listener: () -> Unit = { runOnUiThread { if (!isFinishing && !isDestroyed) changed() } }

    private val saverChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (state == State.SAVER) evaluate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saverAccepted = savedInstanceState?.getBoolean(STATE_SAVER_ACCEPTED) ?: false
        started = savedInstanceState?.getBoolean(STATE_STARTED) ?: false
        still = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.getBooleanExtra(EXTRA_STILL, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_calibration)
        page = findViewById(R.id.calibration_page)
        title = findViewById(R.id.calibration_title)
        text = findViewById(R.id.calibration_text)
        progressBox = findViewById(R.id.calibration_progress)
        bar = findViewById(R.id.calibration_bar)
        step = findViewById(R.id.calibration_step)
        round = findViewById(R.id.calibration_round)
        cancel = findViewById(R.id.calibration_cancel)
        keep = findViewById(R.id.calibration_keep)
        anyway = findViewById(R.id.calibration_anyway)
        background = findViewById(R.id.calibration_background)
        saverSettings = findViewById(R.id.calibration_saver_settings)
        close = findViewById(R.id.calibration_close)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) wide()
        page.held = still

        cancel.setOnClickListener {
            control.cancel()
            control.decline()
            finish()
        }
        keep.setOnClickListener {
            control.cancel()
            control.skip()
            finish()
        }
        background.setOnClickListener {
            control.background()
            finish()
        }
        anyway.setOnClickListener {
            saverAccepted = true
            evaluate()
        }
        saverSettings.setOnClickListener { openSaverSettings() }
        close.setOnClickListener {
            control.decline()
            finish()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // While it runs, the app stays locked behind this screen; otherwise Back cancels.
            override fun handleOnBackPressed() {
                if (state == State.RUNNING) return
                control.decline()
                finish()
            }
        })
    }

    /** Side by side on a wide screen: the page on the start side, the rest beside it. */
    private fun wide() {
        val root = findViewById<LinearLayout>(R.id.calibration_root)
        root.orientation = LinearLayout.HORIZONTAL
        page.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        val scroll = findViewById<View>(R.id.calibration_scroll)
        scroll.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = (24 * resources.displayMetrics.density).toInt()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SAVER_ACCEPTED, saverAccepted)
        outState.putBoolean(STATE_STARTED, started)
    }

    override fun onStart() {
        super.onStart()
        control.listen(listener)
        ContextCompat.registerReceiver(this, saverChanged, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        control.unlisten(listener)
        unregisterReceiver(saverChanged)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        evaluate()
    }

    /** Shows the run in progress, or starts one when nothing stops it, or says what does. */
    private fun evaluate() {
        if (control.running) {
            started = true
            show(State.RUNNING)
            return
        }
        if (state == State.FAILED) return
        if (started && control.lastEnd?.ok == true) {
            finish()
            return
        }
        val r = readings(this)
        when (CalibrationPrompts.gate(r.saverOn, r.thermalStatus, r.batteryPercent, saverAccepted)) {
            CalibrationGate.GO -> {
                started = true
                show(State.RUNNING)
                control.start(underSaver = saverAccepted)
            }
            CalibrationGate.SAVER -> show(State.SAVER)
            CalibrationGate.HOT -> show(State.HOT)
            CalibrationGate.LOW_BATTERY -> show(State.LOW_BATTERY)
        }
    }

    /** The run moved on or ended: progress, or the screen closes once it finished, or says why it could not. */
    private fun changed() {
        if (control.running) {
            if (started) show(State.RUNNING)
            return
        }
        if (!started) return
        val end = control.lastEnd ?: return
        when {
            end.ok || end.canceled -> finish()
            // Left or a book opened: it starts again once this screen is back.
            end.away -> if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) evaluate()
            else -> show(State.FAILED)
        }
    }

    private fun show(s: State) {
        state = s
        val running = s == State.RUNNING
        progressBox.visibility = if (running) View.VISIBLE else View.GONE
        cancel.visibility = if (running || s == State.SAVER) View.VISIBLE else View.GONE
        keep.visibility = if (running && control.calibrated) View.VISIBLE else View.GONE
        background.visibility = if (running) View.VISIBLE else View.GONE
        anyway.visibility = if (s == State.SAVER) View.VISIBLE else View.GONE
        saverSettings.visibility = if (s == State.SAVER) View.VISIBLE else View.GONE
        close.visibility = if (s == State.HOT || s == State.LOW_BATTERY || s == State.FAILED) View.VISIBLE else View.GONE
        when (s) {
            State.RUNNING -> {
                title.setText(R.string.calibration_title)
                text.text = getString(R.string.calibration_text, control.estimateSeconds())
                showProgress()
            }
            State.SAVER -> {
                title.setText(R.string.calibration_saver_title)
                text.setText(R.string.calibration_saver_text)
            }
            State.HOT -> {
                title.setText(R.string.calibration_hot_title)
                text.setText(R.string.calibration_hot_text)
            }
            State.LOW_BATTERY -> {
                title.setText(R.string.calibration_low_title)
                text.text = getString(R.string.calibration_low_text, CalibrationPrompts.LOW_BATTERY_PERCENT)
            }
            State.FAILED -> {
                title.setText(R.string.calibration_failed_title)
                val why = getString(if (control.lastEnd?.hot == true) R.string.calibration_failed_hot else R.string.calibration_failed_busy)
                val after = getString(if (control.calibrated) R.string.calibration_kept_previous else R.string.calibration_kept_defaults)
                text.text = "$why $after"
            }
        }
    }

    private fun showProgress() {
        val p = control.progress
        val c = p.candidate
        if (!p.timing || p.total <= 0) {
            bar.setProgressCompat(0, false)
            step.setText(R.string.calibration_getting_ready)
            round.text = ""
        } else {
            bar.setProgressCompat((p.done * 1000L / p.total).toInt(), true)
            step.text = c?.let { SetupNames.of(resources, it) } ?: ""
            round.text = getString(R.string.calibration_round, p.round, Calibration.ROUNDS)
        }
        page.held = still || (HOLD_FOR_GPU && c?.gpu == true)
    }

    /** Battery saver's own settings, or the system's where there are none. */
    private fun openSaverSettings() {
        val saver = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        runCatching { startActivity(saver) }.onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    companion object {
        /** What the screen drives: [Calibrator]; tests put a fake in. */
        var control: CalibrationControl = Calibrator

        /** Battery saver, heat and battery level now; tests put fakes in. */
        var readings: (Context) -> DeviceReadings = Calibrator::readings

        /** Debuggable builds: hold the page still, to compare timings with and without it moving. */
        const val EXTRA_STILL = "still"

        /** Whether the page holds still while the GPU resampler is timed. */
        const val HOLD_FOR_GPU = false

        private const val STATE_SAVER_ACCEPTED = "saver_accepted"
        private const val STATE_STARTED = "started"

        fun intent(context: Context): Intent = Intent(context, CalibrationScreenActivity::class.java)
    }
}
