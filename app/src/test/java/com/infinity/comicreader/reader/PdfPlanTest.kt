package com.infinity.comicreader.reader

import android.app.Application
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PdfPlanTest {

    /** A US Letter page, in points. */
    private val pw = 612.0
    private val ph = 792.0

    private fun plan(target: Target) = PdfSource.vectorTarget(target, pw).plan(pw, ph)

    @Test
    fun shrinkStillEnlargesAVectorPage() {
        val plan = plan(Target(2400, 3200, Fit.SHRINK))
        assertEquals(2400, plan.width)
        assertEquals(3106, plan.height)
    }

    @Test
    fun noSizeRendersAtTheNativeScale() {
        for (fit in listOf(Fit.INSIDE, Fit.SHRINK)) {
            val plan = plan(Target(fit = fit))
            assertEquals(1224, plan.width)
            assertEquals(1584, plan.height)
        }
    }

    @Test
    fun fitHeightFillsTheHeight() {
        val plan = plan(Target(0, 2960))
        assertEquals(2960, plan.height)
        assertEquals(2287, plan.width)
    }

    @Test
    fun transformFillsTheBitmapWithThePlannedSpan() {
        val pts = floatArrayOf(100f, 50f, 300f, 150f)
        PdfSource.transform(Plan(1000, 500, 100.0, 50.0, 200.0, 100.0)).mapPoints(pts)
        assertEquals(listOf(0f, 0f, 1000f, 500f), pts.map { Math.round(it * 1000) / 1000f })
    }

    @Test
    fun layoutCropsToTheContentFoundInTheFirstRender() {
        val firsts = ArrayList<Plan>()
        val plan = PdfSource.plan(Target(1000, 0, autoCrop = true), pw, ph) {
            firsts += it
            Rect(100, 50, 900, 1200)
        }
        assertEquals(listOf(Plan(1000, 1294, 0.0, 0.0, pw, ph)), firsts)
        val sy = ph / 1294
        assertEquals(1000, plan.width)
        assertEquals(1438, plan.height)
        assertEquals(100 * 0.612, plan.x, 1e-9)
        assertEquals(50 * sy, plan.y, 1e-9)
        assertEquals(800 * 0.612, plan.spanW, 1e-9)
        assertEquals(1150 * sy, plan.spanH, 1e-9)
        // The detail's whole region at the page's size is the page's own plan.
        assertEquals(plan, Region(0.0, 0.0, 1.0, 1.0).plan(plan.width, plan.height, plan.x, plan.y, plan.spanW, plan.spanH))
    }

    @Test
    fun layoutWithoutAutoCropOrContentIsTheWholePage() {
        val whole = Plan(1000, 1294, 0.0, 0.0, pw, ph)
        assertEquals(whole, PdfSource.plan(Target(1000, 0), pw, ph) { throw AssertionError("No first render without auto-crop") })
        assertEquals(whole, PdfSource.plan(Target(1000, 0, autoCrop = true), pw, ph) { null })
    }

    @Test
    fun cropRenderFillsTheTargetWithTheContent() {
        val target = PdfSource.vectorTarget(Target(2400, 3200, Fit.SHRINK, autoCrop = true), pw)
        val first = target.plan(pw, ph)
        // Content inset by an eighth of the first render on every side.
        val l = first.width / 8
        val t = first.height / 8
        val w = first.width - 2 * l
        val h = first.height - 2 * t
        val crop = PdfSource.cropPlan(target, first, l, t, w, h)
        val sx = pw / first.width
        val sy = ph / first.height
        assertEquals(l * sx, crop.x, 1e-9)
        assertEquals(t * sy, crop.y, 1e-9)
        assertEquals(w * sx, crop.spanW, 1e-9)
        assertEquals(h * sy, crop.spanH, 1e-9)
        // The content, not the whole page, now meets the box.
        assertEquals(2400, crop.width)
        val pts = floatArrayOf(crop.x.toFloat(), crop.y.toFloat(), (crop.x + crop.spanW).toFloat(), (crop.y + crop.spanH).toFloat())
        PdfSource.transform(crop).mapPoints(pts)
        assertEquals(0f, pts[0], 0.01f)
        assertEquals(0f, pts[1], 0.01f)
        assertEquals(crop.width.toFloat(), pts[2], 0.01f)
        assertEquals(crop.height.toFloat(), pts[3], 0.01f)
    }
}
