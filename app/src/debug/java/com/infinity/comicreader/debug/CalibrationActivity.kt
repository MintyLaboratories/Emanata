package com.infinity.comicreader.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.infinity.comicreader.reader.Calibrator
import com.infinity.comicreader.reader.CpuCores
import com.infinity.comicreader.reader.JxlPages
import com.infinity.comicreader.reader.NativeResampler
import com.infinity.comicreader.reader.PlatformPages
import org.json.JSONObject

/**
 * Debug and bench builds only: what per-device calibration ([Calibrator]) chose and timed,
 * and a Recalibrate button (calibrates now, charging or not, once the app is idle). Only
 * the shell may start it:
 *
 * `adb shell am start -n <package>/com.infinity.comicreader.debug.CalibrationActivity [--es do recalibrate]
 *  [--ei burst 3] [--ez warm true]`
 *
 * `burst` and `warm` shape a recalibration's samples (runs per sample, an untimed run before
 * them), to compare against the default (3, true); the result keeps them.
 *
 * Each run's end is logged under "Calibration" as "ended true" or "ended false: " and why
 * (shown here too), a run a Recalibrate dropped as "dropped for a new run" ([Calibrator]).
 * Recalibrate goes on under battery saver; the result is marked so.
 */
class CalibrationActivity : Activity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (16 * resources.displayMetrics.density).toInt()
        text = TextView(this).apply { setTextIsSelectable(true) }
        val button = Button(this).apply {
            text = "Recalibrate"
            setOnClickListener { recalibrate() }
        }
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@CalibrationActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                addView(button)
                addView(text)
            })
        })
        Calibrator.onEnded = refresh
        if (savedInstanceState == null && intent.getStringExtra("do") == "recalibrate") recalibrate(intent)
        show()
    }

    /** A start while this one is on top: recalibrate when asked. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("do") == "recalibrate") recalibrate(intent)
    }

    private val refresh: (Boolean) -> Unit = { runOnUiThread { show() } }

    override fun onDestroy() {
        // A newer instance may have taken over.
        if (Calibrator.onEnded === refresh) Calibrator.onEnded = null
        super.onDestroy()
    }

    private fun recalibrate(asked: Intent? = null) {
        Calibrator.recalibrate(asked?.getIntExtra("burst", 3) ?: 3, asked?.getBooleanExtra("warm", true) ?: true)
        show()
    }

    private fun show() {
        val cores = CpuCores.device
        val kept = Calibrator.kept
        text.text = buildString {
            appendLine(if (Calibrator.running) "Calibrating…" else if (Calibrator.due()) "Due" else "Calibrated")
            Calibrator.outcome?.let { appendLine("Last run: $it") }
            appendLine("Tuning: ${Calibrator.tuning?.toJson()}")
            appendLine(
                "In effect: pixels ${NativeResampler.engine.key} ${NativeResampler.threads}/${NativeResampler.laterThreads} " +
                    "pinned ${NativeResampler.pinned}; jxl ${JxlPages.threads}/${JxlPages.laterThreads} pinned ${JxlPages.pinned}; " +
                    "platform JPEG pinned ${PlatformPages.jpegPinned}"
            )
            appendLine("Cores: large ${java.lang.Long.toBinaryString(cores.big)} little ${java.lang.Long.toBinaryString(cores.little)} prime ${java.lang.Long.toBinaryString(cores.prime)}")
            appendLine()
            if (kept == null) appendLine("No calibration kept") else {
                appendLine(
                    "Kept: ${kept.optString("fingerprint")}, ${kept.optLong("tookMs")} ms, forced ${kept.optBoolean("forced")}, " +
                        "battery saver ${kept.optBoolean("powerSave")}, thermal ${kept.optInt("thermal", -1)}, page ${kept.optString("page", "?")} " +
                        "to ${kept.optString("target", "?")}, burst ${kept.optInt("burst", 3)} warm ${kept.optBoolean("warm", true)}"
                )
                val timings = kept.optJSONArray("timings")
                for (i in 0 until (timings?.length() ?: 0)) {
                    val t: JSONObject = timings!!.getJSONObject(i)
                    appendLine("%-16s %7.1f ms wall %7.1f ms CPU".format(t.getString("setup"), t.getDouble("wallMs"), t.getDouble("cpuMs")))
                }
            }
        }
    }
}
