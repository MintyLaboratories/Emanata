package minty.emanata.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Calls [onChange] on the main thread whenever a storage volume is mounted, ejected
 * or pulled out: through the storage-volume callback on Android 11+, the media
 * broadcasts before that. Watches between [start] and [stop].
 */
class StorageWatcher(private val context: Context, private val onChange: () -> Unit) {
    private var callback: Any? = null
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (callback != null || receiver != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            callback = register()
        } else {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onChange()
            }
            val filter = IntentFilter().apply {
                for (action in MEDIA_ACTIONS) addAction(action)
                addDataScheme("file")
            }
            ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
            receiver = r
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (callback as? StorageManager.StorageVolumeCallback)?.let { cb -> runCatching { manager()?.unregisterStorageVolumeCallback(cb) } }
        }
        callback = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun register(): StorageManager.StorageVolumeCallback? {
        val manager = manager() ?: return null
        val cb = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) = onChange()
        }
        // Not every platform build (or test runtime) offers it; volumes then refresh on resume.
        return cb.takeIf { runCatching { manager.registerStorageVolumeCallback(ContextCompat.getMainExecutor(context), cb) }.isSuccess }
    }

    private fun manager(): StorageManager? = context.getSystemService(StorageManager::class.java)

    private companion object {
        val MEDIA_ACTIONS = listOf(
            Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL
        )
    }
}
