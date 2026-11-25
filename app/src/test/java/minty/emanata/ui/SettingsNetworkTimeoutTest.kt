package minty.emanata.ui

import android.app.Application
import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.data.Prefs
import minty.emanata.net.Remotes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The network timeout set in Settings applies to shares at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsNetworkTimeoutTest {

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Application>()).edit().clear().commit()
        Remotes.answerMs = Remotes.DEFAULT_ANSWER_MS
    }

    @After
    fun tearDown() {
        Remotes.answerMs = Remotes.DEFAULT_ANSWER_MS
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settings(): PreferenceFragmentCompat {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        return activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
    }

    @Test
    fun aNewTimeoutAppliesAtOnceAndResetRestoresTheDefault() {
        val fragment = settings()
        val timeout = fragment.findPreference<Preference>(Prefs.KEY_NETWORK_TIMEOUT)!!
        timeout.callChangeListener("60")
        assertEquals(60_000L, Remotes.answerMs)

        val reset = fragment.findPreference<Preference>("pref_reset")!!
        reset.onPreferenceClickListener!!.onPreferenceClick(reset)
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(Remotes.DEFAULT_ANSWER_MS, Remotes.answerMs)
    }
}
