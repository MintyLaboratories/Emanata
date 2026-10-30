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
 * Keeps the app running while file operations wait or run ([FileWork]), so a copy goes on
 * with the app in the background or the screen off: a foreground service (data sync)
 * showing the progress of the one under way and how many wait, with Cancel while canceling
 * can stop it, and a partial wake lock that lapses after [WAKE_MS] without progress. Stops
 * as the last one ends.
 */
class FileWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var wake: PowerManager.WakeLock? = null

    /** When the notification was last updated, by [FileWork.clock], and the operation it showed. */
    private var shownAt = 0L
    private var shownId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FileWork.cancel()
            return START_NOT_STICKY
        }
        val operations = FileWork.operations.value
        // Started as a foreground service: in the foreground at once, even to stop.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(operations), TYPE)
        } catch (e: IllegalStateException) {
            // Refused (the app went to the background first): the operations run unkept.
            stopSelf()
            return START_NOT_STICKY
        }
        if (operations.none { it.outcome == null }) {
            finish()
            return START_NOT_STICKY
        }
        hold()
        if (watching == null) watching = scope.launch { FileWork.operations.collect(::follow) }
        return START_NOT_STICKY
    }

    private fun follow(operations: List<FileWork.Status>) {
        if (operations.none { it.outcome == null }) return finish()
        hold()
        val now = FileWork.clock()
        val current = operations.firstOrNull { it.running }?.id
        // Another operation starting shows at once.
        if (now - shownAt < UPDATE_MS && current == shownId) return
        shownAt = now
        shownId = current
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(operations))
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

    /** The notification for the operation under way among [operations], saying how many more wait. */
    private fun notification(operations: List<FileWork.Status>): Notification {
        val status = operations.firstOrNull { it.running } ?: operations.firstOrNull { it.waiting }
        val more = operations.count { it.waiting && it.id != status?.id }
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
            .setContentText(status?.let { nameOf(this, it) })
            .setSubText(if (more > 0) resources.getQuantityString(R.plurals.file_more_waiting, more, more) else null)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        val fraction = status?.fraction
        if (fraction != null) {
            builder.setProgress(PROGRESS_STEPS, (fraction * PROGRESS_STEPS).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        val copying = status?.kind == FileWork.Kind.COPY || status?.kind == FileWork.Kind.MOVE
        // Several items stop between two.
        if ((copying || (status?.count ?: 1) > 1) && status?.stoppable == true && status.running) {
            val cancel = PendingIntent.getService(
                this, 0, Intent(this, FileWorkService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, getString(R.string.cancel), cancel)
        }
        return builder.build()
    }

    companion object {
        /** What an operation of [kind] is doing, as a title: "Copying". */
        fun titleOf(kind: FileWork.Kind?): Int = when (kind) {
            FileWork.Kind.MOVE -> R.string.file_moving
            FileWork.Kind.DELETE -> R.string.file_deleting
            FileWork.Kind.RENAME -> R.string.file_renaming
            else -> R.string.file_copying
        }

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

        /** What [status] works on: the item's name, and which of how many when there are several. */
        fun nameOf(context: Context, status: FileWork.Status): String =
            if (status.count > 1) context.getString(R.string.file_item_of, status.name, status.index + 1, status.count) else status.name

        /** Starts the service for the operation just queued; ignored where Android refuses it. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, FileWorkService::class.java)) }
        }
    }
}
