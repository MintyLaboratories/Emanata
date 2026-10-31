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
import org.json.JSONObject

/**
 * Debug and bench builds only: what per-device calibration ([Calibrator]) chose and timed,
 * and a Recalibrate button (calibrates now, charging or not, once the app is idle). Only
 * the shell may start it:
 *
 * `adb shell am start -n <package>/com.infinity.comicreader.debug.CalibrationActivity [--es do recalibrate]`
 *
 * Each run's end is logged under "Calibration" as "ended true" or "ended false" ([Calibrator]).
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
        if (savedInstanceState == null && intent.getStringExtra("do") == "recalibrate") recalibrate()
        show()
    }

    /** A start while this one is on top: recalibrate when asked. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("do") == "recalibrate") recalibrate()
    }

    private val refresh: (Boolean) -> Unit = { runOnUiThread { show() } }

    override fun onDestroy() {
        // A newer instance may have taken over.
        if (Calibrator.onEnded === refresh) Calibrator.onEnded = null
        super.onDestroy()
    }

    private fun recalibrate() {
        Calibrator.recalibrate()
        show()
    }

    private fun show() {
        val cores = CpuCores.device
        val kept = Calibrator.kept
        text.text = buildString {
            appendLine(if (Calibrator.running) "Calibrating…" else if (Calibrator.due()) "Due" else "Calibrated")
            appendLine("Tuning: ${Calibrator.tuning?.toJson()}")
            appendLine(
                "In effect: pixels ${NativeResampler.engine.key} ${NativeResampler.threads}/${NativeResampler.laterThreads} " +
                    "pinned ${NativeResampler.pinned}; jxl ${JxlPages.threads}/${JxlPages.laterThreads} pinned ${JxlPages.pinned}"
            )
            appendLine("Cores: large ${java.lang.Long.toBinaryString(cores.big)} little ${java.lang.Long.toBinaryString(cores.little)} prime ${java.lang.Long.toBinaryString(cores.prime)}")
            appendLine()
            if (kept == null) appendLine("No calibration kept") else {
                appendLine("Kept: ${kept.optString("fingerprint")}, ${kept.optLong("tookMs")} ms, forced ${kept.optBoolean("forced")}")
                val timings = kept.optJSONArray("timings")
                for (i in 0 until (timings?.length() ?: 0)) {
                    val t: JSONObject = timings!!.getJSONObject(i)
                    appendLine("%-16s %7.1f ms wall %7.1f ms CPU".format(t.getString("setup"), t.getDouble("wallMs"), t.getDouble("cpuMs")))
                }
            }
        }
    }
}
