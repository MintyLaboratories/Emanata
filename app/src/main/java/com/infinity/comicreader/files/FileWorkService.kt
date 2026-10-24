package com.infinity.comicreader.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.ui.ListActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app running while a file operation runs ([FileWork]), so a copy goes on with
 * the app in the background or the screen off: a foreground service (data sync) showing
 * the operation's progress, with Cancel while canceling can stop it, and a partial wake
 * lock that lapses after [WAKE_MS] without progress. Stops as the operation ends.
 */
class FileWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var wake: PowerManager.WakeLock? = null

    /** When the notification was last updated, by [FileWork.clock]. */
    private var shownAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FileWork.cancel()
            return START_NOT_STICKY
        }
        val status = FileWork.status.value
        // Started as a foreground service: in the foreground at once, even to stop.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(status), TYPE)
        if (status == null || status.outcome != null) {
            finish()
            return START_NOT_STICKY
        }
        hold()
        if (watching == null) watching = scope.launch { FileWork.status.collect(::follow) }
        return START_NOT_STICKY
    }

    private fun follow(status: FileWork.Status?) {
        if (status == null || status.outcome != null) return finish()
        hold()
        val now = FileWork.clock()
        if (now - shownAt < UPDATE_MS) return
        shownAt = now
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(status))
    }

    /** Keeps the device awake for [WAKE_MS] more. */
    private fun hold() {
        val lock = wake ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
            ?.apply { setReferenceCounted(false) }
            ?.also { wake = it }
        lock?.acquire(WAKE_MS)
    }

    private fun finish() {
        watching?.cancel()
        watching = null
        wake?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15+ ends a data sync service after 6 hours a day: the operation goes on, unkept. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(status: FileWork.Status?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.file_work_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (status?.kind == FileWork.Kind.MOVE) R.drawable.ic_drive_file_move else R.drawable.ic_content_copy)
            .setContentTitle(getString(titleOf(status?.kind)))
            .setContentText(status?.name)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        if (status != null && status.total > 0) {
            builder.setProgress(PROGRESS_STEPS, (status.done.coerceIn(0, status.total) * PROGRESS_STEPS / status.total).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val copying = status?.kind == FileWork.Kind.COPY || status?.kind == FileWork.Kind.MOVE
        if (copying && status?.stoppable == true) {
            val cancel = PendingIntent.getService(
                this, 0, Intent(this, FileWorkService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.cancel), cancel)
        }
        return builder.build()
    }

    private fun titleOf(kind: FileWork.Kind?): Int = when (kind) {
        FileWork.Kind.MOVE -> R.string.file_moving
        FileWork.Kind.DELETE -> R.string.file_deleting
        FileWork.Kind.RENAME -> R.string.file_renaming
        else -> R.string.file_copying
    }

    companion object {
        private const val CHANNEL = "file_work"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_CANCEL = "cancel"
        private const val WAKE_TAG = "emanata:file-work"

        /** How long the device stays awake after the last progress: a stalled operation lets it sleep. */
        const val WAKE_MS = 10 * 60_000L

        /** How often the notification shows progress. */
        private const val UPDATE_MS = 1_000L
        private const val PROGRESS_STEPS = 1000

        private val TYPE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        /** Starts the service for the operation that just started; ignored where Android refuses it. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, FileWorkService::class.java)) }
        }
    }
}
