package minty.emanata.ui

import android.os.Looper
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import minty.emanata.R
import minty.emanata.reader.Calibrator
import minty.emanata.testing.FakeCalibration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings › Performance › Recalibrate opens the calibration screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRecalibrateTest {
    @Before
    fun setUp() {
        CalibrationScreenActivity.control = FakeCalibration(calibrated = true)
    }

    @After
    fun tearDown() {
        CalibrationScreenActivity.control = Calibrator
    }

    @Test
    fun recalibrateOpensTheCalibrationScreen() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        val pref = fragment.findPreference<Preference>("pref_recalibrate")!!
        assertTrue(pref.summary.toString().endsWith("(about 25 s)"))
        pref.onPreferenceClickListener!!.onPreferenceClick(pref)
        assertEquals(CalibrationScreenActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
    }
}
