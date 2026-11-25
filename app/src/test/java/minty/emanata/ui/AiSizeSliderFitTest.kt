package minty.emanata.ui

import android.app.Application
import android.graphics.Paint
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.ai.AiThresholds
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The AI size slider's bubbles and axis labels fit at large font scales on a phone-width view. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiSizeSliderFitTest {

    /** A slider [widthDp] wide on a phone at [fontScale], laid out. */
    private fun slider(fontScale: Float, widthDp: Int): AiSizeSlider {
        RuntimeEnvironment.setQualifiers("w400dp-h800dp-xxhdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_ComicReader)
        val width = (widthDp * context.resources.displayMetrics.density).toInt()
        return AiSizeSlider(context).apply {
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            layout(0, 0, width, measuredHeight)
        }
    }

    private fun check(fontScale: Float, widthDp: Int) {
        val what = "$widthDp dp at $fontScale"
        val s = slider(fontScale, widthDp)
        val dm = s.resources.displayMetrics
        val text = Paint().apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, dm) }.fontMetrics
        assertTrue("$what: bubble holds its text", s.bubbleH >= text.descent - text.ascent)
        val shown = s.shownLabels()
        assertTrue("$what: the ends and 100% are labeled, $shown", shown.containsAll(listOf(AiThresholds.ALWAYS, 100, 200)))
        for (pct in shown) {
            val span = s.labelSpan(pct)
            assertTrue("$what: $pct% inside the view", span.start >= 0f && span.endInclusive <= s.width)
        }
        shown.zipWithNext { a, b -> assertTrue("$what: $a% and $b% apart", s.labelSpan(a).endInclusive < s.labelSpan(b).start) }
    }

    @Test
    fun labelsAndBubblesFitAtEveryFontScale() {
        // 400 dp less the settings list's side padding.
        for (scale in listOf(1.0f, 1.3f, 2.0f)) check(scale, 368)
    }

    @Test
    fun aNarrowViewAtTwiceTheTextDropsALabel() {
        check(2.0f, 368)
        assertTrue(slider(2.0f, 368).shownLabels().size < 5)
    }
}
