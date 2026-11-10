package com.infinity.comicreader.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.infinity.comicreader.data.Prefs

/**
 * Sets debug-only flags from the shell, kept across restarts, and answers with their state:
 * `am broadcast -n <package>/com.infinity.comicreader.debug.DebugFlagsReceiver --ez no_play_services true`
 * plays a device without Google Play services ([Prefs.debugNoPlayServices]); no extra only tells.
 */
class DebugFlagsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (intent.hasExtra(NO_PLAY_SERVICES)) prefs.debugNoPlayServices = intent.getBooleanExtra(NO_PLAY_SERVICES, false)
        val state = "$NO_PLAY_SERVICES=${prefs.debugNoPlayServices}"
        Log.i(TAG, state)
        if (isOrderedBroadcast) resultData = state
    }

    companion object {
        private const val TAG = "DebugFlags"
        const val NO_PLAY_SERVICES = "no_play_services"
    }
}
