package minty.emanata.data

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.testing.awaitUntil
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.ref.WeakReference

/**
 * A listener registered through [Prefs] hears changes after a garbage collection even when
 * nothing else holds it, as in a minified build, where R8 removes [SettingsRepository]'s field
 * for its listener and the settings feed went silent at the first collection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsListenerTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        prefs = Prefs(context)
    }

    @Test
    fun listenerHeldOnlyByPrefsHearsChangesAfterCollection() {
        val heard = mutableListOf<String?>()
        // Made in another frame, so no local of this one holds the listener.
        val sentinel = register(heard)
        // A collection ran once an object reachable only weakly, made with the listener, is gone.
        awaitUntil("a garbage collection") {
            System.gc()
            sentinel.get() == null
        }

        prefs.doubleTapZoom = false
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf<String?>("pref_double_tap"), heard)
    }

    /** Registers a listener recording changed keys into [heard]; returns a weak reference to an object made with it. */
    private fun register(heard: MutableList<String?>): WeakReference<Any> {
        prefs.registerListener(SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard += key })
        return WeakReference(Any())
    }
}
