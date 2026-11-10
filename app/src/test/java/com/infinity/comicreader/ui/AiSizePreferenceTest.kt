package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiThresholds
import com.infinity.comicreader.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings › AI processing › Models by page size: follows AI processing, stored when a handle is let go, its sentence live. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiSizePreferenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun settings(): PreferenceFragmentCompat {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
    }

    private fun PreferenceFragmentCompat.sizes() = findPreference<AiSizePreference>("pref_ai_sizes")!!

    /** [fragment]'s size setting bound to a fresh view, laid out 1000 px wide. */
    private fun bind(fragment: PreferenceFragmentCompat): View {
        val view = LayoutInflater.from(fragment.requireContext()).inflate(R.layout.preference_ai_sizes, null)
        fragment.sizes().onBindViewHolder(PreferenceViewHolder.createInstanceForTests(view))
        view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 1000, view.measuredHeight)
        return view
    }

    private fun touch(slider: AiSizeSlider, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        slider.onTouchEvent(MotionEvent.obtain(now, now, action, x, y, 0))
    }

    @Test
    fun followsAiProcessingInTheAiSection() {
        val fragment = settings()
        val pref = fragment.sizes()
        assertEquals("section_ai", pref.parent!!.key)
        assertTrue(AdvancedSettings.isAdvanced(pref))
        assertFalse(pref.isEnabled)
        fragment.findPreference<TwoStatePreference>(Prefs.KEY_AI)!!.performClick()
        assertTrue(pref.isEnabled)
    }

    @Test
    fun withAiProcessingOffTheSliderIgnoresTouches() {
        val view = bind(settings())
        val slider = view.findViewById<AiSizeSlider>(R.id.ai_sizes_slider)
        assertFalse(slider.isEnabled)
        touch(slider, MotionEvent.ACTION_DOWN, 999f, 2f)
        touch(slider, MotionEvent.ACTION_UP, 999f, 2f)
        assertEquals(AiThresholds(), slider.thresholds)
        assertEquals(AiThresholds(), Prefs(context).aiThresholds)
    }

    @Test
    fun theSentencesColorsDimWithTheSlider() {
        fun colors(view: View): List<Int> {
            val text = view.findViewById<TextView>(R.id.ai_sizes_sentence).text as Spanned
            return text.getSpans(0, text.length, ForegroundColorSpan::class.java).sortedBy { text.getSpanStart(it) }.map { it.foregroundColor }
        }
        val fragment = settings()
        val off = bind(fragment)
        val slider = off.findViewById<AiSizeSlider>(R.id.ai_sizes_slider)
        val full = listOf(slider.upscaleColor, slider.cleanupColor)
        assertTrue(colors(off).zip(full).all { (shown, color) -> Color.alpha(shown) < Color.alpha(color) })
        fragment.findPreference<TwoStatePreference>(Prefs.KEY_AI)!!.performClick()
        assertEquals(full, colors(bind(fragment)))
    }

    @Test
    fun showsTheStoredHandlesAndTheirSentence() {
        Prefs(context).aiThresholds = AiThresholds(60, 150)
        val view = bind(settings())
        assertEquals(AiThresholds(60, 150), view.findViewById<AiSizeSlider>(R.id.ai_sizes_slider).thresholds)
        assertEquals(
            AiSizeText.sentence(context, AiThresholds(60, 150), 0, 0).toString(),
            view.findViewById<TextView>(R.id.ai_sizes_sentence).text.toString()
        )
    }

    @Test
    fun aDragShowsLiveAndStoresOnRelease() {
        val fragment = settings()
        fragment.findPreference<TwoStatePreference>(Prefs.KEY_AI)!!.performClick()
        val view = bind(fragment)
        val slider = view.findViewById<AiSizeSlider>(R.id.ai_sizes_slider)
        val sentence = view.findViewById<TextView>(R.id.ai_sizes_sentence)
        val below = slider.height - 2f

        // Drag the cleanup handle to the left end: the sentence follows, nothing is stored yet.
        touch(slider, MotionEvent.ACTION_DOWN, 500f, below)
        for (x in 450 downTo 0 step 50) touch(slider, MotionEvent.ACTION_MOVE, x.toFloat(), below)
        assertEquals(AiThresholds.ALWAYS, slider.thresholds.cleanupPct)
        assertTrue(sentence.text.contains(context.getString(R.string.ai_sizes_cleanup_always)))
        assertEquals(AiThresholds(), Prefs(context).aiThresholds)

        touch(slider, MotionEvent.ACTION_UP, 0f, below)
        assertEquals(AiThresholds(100, AiThresholds.ALWAYS), Prefs(context).aiThresholds)

        // A step stores at once, and a fresh screen shows it.
        assertTrue(slider.step(AiSizeSlider.Handle.UPSCALE, -2))
        assertEquals(AiThresholds(90, AiThresholds.ALWAYS), Prefs(context).aiThresholds)
        assertEquals(AiThresholds(90, AiThresholds.ALWAYS), bind(settings()).findViewById<AiSizeSlider>(R.id.ai_sizes_slider).thresholds)
    }
}
