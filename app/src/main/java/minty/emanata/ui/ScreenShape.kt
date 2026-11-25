package minty.emanata.ui

import android.content.Context
import android.os.Build
import android.view.WindowManager
import minty.emanata.reader.TallPages

/** The screen's shape, whichever way it is turned. */
object ScreenShape {
    /** Height / width of the screen held upright: its largest window, insets included. */
    fun portraitAspect(context: Context): Double {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds?.takeUnless { it.isEmpty }
        } else {
            null
        }
        if (bounds != null) return TallPages.portraitAspect(bounds.width(), bounds.height())
        val metrics = context.resources.displayMetrics
        return TallPages.portraitAspect(metrics.widthPixels, metrics.heightPixels)
    }
}
