package com.infinity.comicreader.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Debug builds only: runs a benchmark ([BenchPlan], [BenchRunner]) in the app's own
 * process, staying behind the reader or library it drives. Only the shell may start it:
 *
 * `adb shell am start -n <package>/com.infinity.comicreader.debug.BenchActivity --es do read (--es path <book> | --es share first) [--es label <name>]
 * [--es layout paged|two|continuous] [--es input tap|key] [--ei page <n>] [--ei turns <n>] [--ei pace <ms>] [--ei reps <n>]
 * [--ez fresh true] [--ez crop true] [--es doubletap on|off]`
 *
 * `adb shell am start -n <package>/com.infinity.comicreader.debug.BenchActivity --es do grid --es path <folder> [--ez fresh true]`
 *
 * Each session is one JSON line, logged under "Bench" and appended to `bench/results.jsonl` in the
 * app's external files ([BenchRunner.results]); "Bench done" ends a run. One run at a time; a start
 * while one runs is ignored. In the bench build (release code, not debuggable) timings are a release build's.
 */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "Benchmark running"
            gravity = Gravity.CENTER
        })
        if (savedInstanceState != null) return
        val plan = BenchPlan.of(intent)
        if (plan == null) {
            Log.w(BenchRunner.TAG, "Nothing to do: give --es do read|grid and --es path")
            Log.i(BenchRunner.TAG, "Bench done")
            finish()
            return
        }
        if (running) {
            Log.w(BenchRunner.TAG, "A run is going on; ignored")
            finish()
            return
        }
        running = true
        scope.launch {
            try {
                for (result in BenchRunner(application).run(this@BenchActivity, plan)) BenchRunner.record(application, result)
            } catch (t: Throwable) {
                Log.w(BenchRunner.TAG, "Bench failed: $t", t)
            } finally {
                running = false
                Log.i(BenchRunner.TAG, "Bench done")
                finish()
            }
        }
    }

    private companion object {
        val scope = MainScope()

        @Volatile
        var running = false
    }
}
