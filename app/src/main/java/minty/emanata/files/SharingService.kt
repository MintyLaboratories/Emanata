package minty.emanata.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import minty.emanata.R
import minty.emanata.ui.ListActivity

/**
 * Keeps the app running while another app may still read the books shared with it
 * ([BookProvider]), so it is not frozen while that app reads a file on a share in the
 * background: a foreground service (data sync) with an ongoing "Sharing…" notification
 * and Stop. Started as the user sends a file, while the app is on screen, as Android
 * requires; ends once no shared file has been asked for or read for [IDLE_MS]
 * ([BookProvider.lastUsed]), counted from the start at the latest.
 */
class SharingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { step() }

    /** When the service was last started, by [SystemClock.elapsedRealtime]. */
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started as a foreground service: in the foreground at once, even to stop.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), TYPE)
        } catch (e: IllegalStateException) {
            // Refused (the app went to the background first): the files are shared unkept.
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        startedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(check)
        handler.postDelayed(check, CHECK_MS)
        return START_NOT_STICKY
    }

    private fun step() {
        if (idle(maxOf(startedAt, BookProvider.lastUsed), SystemClock.elapsedRealtime())) finish() else handler.postDelayed(check, CHECK_MS)
    }

    private fun finish() {
        handler.removeCallbacks(check)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15+ ends a data sync service after 6 hours a day: sharing goes on, unkept. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.sharing_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SharingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_share)
            .setContentTitle(getString(R.string.sharing_title))
            .setContentText(getString(R.string.sharing_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.sharing_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "sharing"
        private const val NOTIFICATION_ID = 7302
        internal const val ACTION_STOP = "stop"

        /** How long the service stays after shared files were last asked for or read. */
        const val IDLE_MS = 2 * 60_000L

        /** How often it checks. */
        const val CHECK_MS = 15_000L

        private val TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        /** True once nothing happened since [last] for [IDLE_MS] at [now]. */
        internal fun idle(last: Long, now: Long): Boolean = now - last >= IDLE_MS

        /** Starts the service, or keeps it on for [IDLE_MS] more; call while the app is on screen. Ignored where Android refuses it. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SharingService::class.java)) }
        }
    }
}
