package minty.emanata.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import minty.emanata.data.Prefs

/**
 * Asks to show notifications (Android 13+), where work going on away from the screen
 * shows (file operations, sharing), once ever for the whole app ([Prefs.askedNotifications]);
 * the work runs either way. Made as [activity] is created.
 */
internal class NotificationAsk(private val activity: AppCompatActivity, private val prefs: Prefs) {
    /** The answer changes nothing here. */
    private val ask = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Asks unless asked before or allowed already. */
    fun once() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || prefs.askedNotifications) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.askedNotifications = true
        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
