package minty.emanata.data

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Every setting the settings screen stores is in a backup and comes back from one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PrefsBackupKeysTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    /** Settings left out of backups on purpose, each with why. */
    private val leftOut = mapOf(
        // Stores nothing under its own key: its values are pref_ai_upscale_above and pref_ai_cleanup_above.
        "pref_ai_sizes" to "not persistent",
    )

    /** The `pref_*` keys of settings.xml's settings that store a value: not plain [androidx.preference.Preference] rows, not marked not persistent. */
    private fun storedKeys(): List<String> {
        val keys = ArrayList<String>()
        val parser = context.resources.getXml(R.xml.settings)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name == "Preference") continue
            var key: String? = null
            var persistent = true
            for (i in 0 until parser.attributeCount) {
                when (parser.getAttributeName(i)) {
                    "key" -> key = parser.getAttributeValue(i)
                    "persistent" -> persistent = parser.getAttributeValue(i) != "false"
                }
            }
            if (key != null && key.startsWith("pref_") && persistent) keys += key
        }
        return keys
    }

    @Test
    fun everyStoredSettingIsBackedUpAndRestored() {
        val keys = storedKeys() - leftOut.keys
        assertTrue(keys.toString(), keys.size > 50 && "pref_ai_dot" in keys && "pref_wide_pages" in keys)
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.edit().apply { keys.forEach { putString(it, "x") } }.commit()
        val prefs = Prefs(context)
        val saved = prefs.exportAll()
        assertEquals(emptyList<String>(), keys.filter { it !in saved })
        sp.edit().clear().commit()
        prefs.importAll(saved)
        assertEquals(emptyList<String>(), keys.filter { !sp.contains(it) })
    }
}
