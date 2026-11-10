package com.infinity.comicreader.ai

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Play services on the device: [available] asks [GoogleApiAvailability] once per session, and
 * again each [ASK_AGAIN_MS] while Play services updates or is disabled; the text recognition
 * module is checked and requested through Play services' [ModuleInstall] client, which starts
 * ML Kit ([MlKitContext]) only then, never before the user turned it on.
 */
class GmsPlayServices(context: Context) : PlayServices {
    private val app = context.applicationContext

    /** The last answer of [GoogleApiAvailability], and when it was asked; null before. */
    @Volatile
    private var there: Pair<Int, Long>? = null

    override fun available(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val known = there
        val result = if (known != null && (known.first !in PASSING || now - known.second < ASK_AGAIN_MS)) known.first
        else (runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) }.getOrDefault(ConnectionResult.SERVICE_MISSING))
            .also { there = it to now }
        return result == ConnectionResult.SUCCESS
    }

    override fun moduleInstalled(): Boolean? {
        val recognizer = runCatching { recognizer() }.getOrElse { Log.w(TAG, "ML Kit does not start", it); return null }
        return try {
            Tasks.await(ModuleInstall.getClient(app).areModulesAvailable(recognizer), ASK_S, TimeUnit.SECONDS).areModulesAvailable()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            Log.w(TAG, "Play services did not tell whether the module is installed", e)
            null
        } finally {
            recognizer.close()
        }
    }

    override fun requestModule(done: (Boolean) -> Unit): () -> Unit {
        val recognizer = runCatching { recognizer() }.getOrElse {
            Log.w(TAG, "ML Kit does not start", it)
            done(false)
            return {}
        }
        val told = AtomicBoolean(false)
        val client = ModuleInstall.getClient(app)
        lateinit var listener: InstallStatusListener
        val finish = { installed: Boolean ->
            if (told.compareAndSet(false, true)) {
                runCatching { client.unregisterListener(listener) }
                recognizer.close()
                done(installed)
            }
        }
        listener = InstallStatusListener { update ->
            when (update.installState) {
                InstallState.STATE_COMPLETED -> finish(true)
                InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> finish(false)
            }
        }
        val request = ModuleInstallRequest.newBuilder().addApi(recognizer).setListener(listener, worker).build()
        client.installModules(request)
            .addOnSuccessListener(worker) { if (it.areModulesAlreadyInstalled()) finish(true) }
            .addOnFailureListener(worker) {
                Log.w(TAG, "Play services refused the module", it)
                finish(false)
            }
        return { finish(false) }
    }

    /** A recognizer client: what the module install client asks about; closed by the caller. */
    private fun recognizer(): TextRecognizer {
        MlKitContext.initializeIfNeeded(app)
        return TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private companion object {
        const val TAG = "GmsPlayServices"

        /** Answers of [GoogleApiAvailability] that may turn to SUCCESS without a restart: asked again after [ASK_AGAIN_MS]. */
        val PASSING = setOf(ConnectionResult.SERVICE_UPDATING, ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_DISABLED)

        const val ASK_AGAIN_MS = 60_000L

        /** How long Play services may take to say whether the module is installed. */
        const val ASK_S = 10L

        /** Where install updates arrive: off the main thread. */
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "play-services").apply { isDaemon = true } }
    }
}
