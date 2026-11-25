package minty.emanata.ui

import android.app.Application
import android.view.View
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.ai.AiLoad
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The load estimate bar: a color per level, and plain words for TalkBack, never numbers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiLoadBarTest {

    private val context = ApplicationProvider.getApplicationContext<Application>().apply { setTheme(R.style.Theme_ComicReader) }

    @Test
    fun talkBackReadsTheLevelInWords() {
        val bar = AiLoadBar(context)
        assertEquals("Load estimate: light", bar.contentDescription)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, bar.importantForAccessibility)
        bar.pageMs = 12_000
        assertEquals("Load estimate: moderate", bar.contentDescription)
        bar.pageMs = 25_000
        assertEquals("Load estimate: heavy", bar.contentDescription)
        bar.pageMs = 3_400
        assertEquals("Load estimate: light", bar.contentDescription)
        assertEquals(3_400L, bar.pageMs)
    }

    @Test
    fun eachLevelHasItsColor() {
        assertEquals(R.color.ai_load_light, AiLoadBar.colorOf(AiLoad.Level.LIGHT))
        assertEquals(R.color.ai_load_moderate, AiLoadBar.colorOf(AiLoad.Level.MODERATE))
        assertEquals(R.color.ai_load_heavy, AiLoadBar.colorOf(AiLoad.Level.HEAVY))
    }

    @Test
    fun theBarIsThinAndAsWideAsItIsGiven() {
        val bar = AiLoadBar(context)
        bar.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(600, bar.measuredWidth)
        assertEquals(context.resources.getDimensionPixelSize(R.dimen.ai_load_bar_height), bar.measuredHeight)
    }
}
