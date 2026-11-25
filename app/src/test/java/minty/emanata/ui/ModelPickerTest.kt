package minty.emanata.ui

import android.app.Application
import android.app.Dialog
import android.content.res.Configuration
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.slider.Slider
import minty.emanata.R
import minty.emanata.ai.AiStep
import minty.emanata.ai.ModelCatalog
import minty.emanata.ai.ModelRatings
import minty.emanata.ai.Models
import minty.emanata.ai.RatingCategory
import minty.emanata.ai.ShaderCatalog
import minty.emanata.ai.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.roundToInt

/** AI model pickers: short names, speed, an info button that shows and hides ratings and description, a level slider per family. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelPickerTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val context = ContextThemeWrapper(app, R.style.Theme_Emanata)

    private val picks = ArrayList<Pair<String?, Boolean>>()

    private fun picker(step: AiStep, keepSimple: Boolean = true, current: String? = null, ctx: android.content.Context = context) =
        ModelPicker.of(ctx, step, keepSimple, current) { id, done -> picks += id to done }

    private fun rows(p: ModelPicker): List<View> = p.view.findViewById<LinearLayout>(R.id.ai_model_list).children.toList()

    private fun rowOf(p: ModelPicker, name: String): View = rows(p).first { name(it) == name }

    private fun details(row: View): View = row.findViewById(R.id.ai_model_details)

    private fun info(row: View): View = row.findViewById(R.id.ai_model_info)

    /** The ratings table's rows: label to stars. */
    private fun ratings(row: View): List<Pair<String, Int>> = row.findViewById<TableLayout>(R.id.ai_model_ratings).children
        .filterIsInstance<TableRow>()
        .map { r -> (r.getChildAt(0) as TextView).text.toString() to ((r.getChildAt(1) as ViewGroup).getChildAt(0) as StarsView).halves }.toList()

    @Test
    fun rowsHaveShortNamesWithoutTheStep() {
        val upscale = rows(picker(AiStep.UPSCALE)).map(::name)
        assertEquals(listOf("None", "waifu2x 2× (default)", "waifu2x denoise 2×", "OpenComic 2×", "OpenComic 3×", "OpenComic 4×", "ArtCNN 2×", "CuNNy 2×"), upscale.take(8))
        assertTrue(upscale.none { it.contains(" AI ") || it.contains("AI Upscale") || it.contains("Fast v2") || it.contains("UpConv7") })
        val all = rows(picker(AiStep.UPSCALE, keepSimple = false)).map(::name)
        assertTrue(all.containsAll(listOf("Real-ESRGAN 2×", "Real-ESRGAN 3×", "Real-ESRGAN 4×", "LSDIR 4×")))
        // Slow ones last.
        assertEquals("LSDIR 4×", all.last())
        assertEquals(listOf("None", "OpenComic"), rows(picker(AiStep.ARTIFACTS)).map(::name))
        assertEquals(listOf("None", "OpenComic v2", "OpenComic v3"), rows(picker(AiStep.DESCREEN, keepSimple = false)).map(::name))
        // Every catalog model has a short name of its own, unique in its step.
        for (step in AiStep.entries) {
            val single = ModelCatalog.forStep(step).filter { it.level == null }
            for (m in single) assertTrue(m.id, PickerRows.shortName(m) != m.name)
            assertEquals(single.size, single.map(PickerRows::shortName).distinct().size)
        }
    }

    @Test
    fun aFamilyIsOneRowWithItsLevels() {
        val rows = PickerRows.of(AiStep.UPSCALE, keepSimple = true, current = null, noneName = "None")
        // Every shader family on one row, each of its levels behind a slider; CuNNy's and ArtCNN's sizes, and the waifu2x denoise strengths, on three more.
        val families = rows.filter { it.family }
        assertEquals(ShaderCatalog.all.map { it.level.family }.distinct().size + 3, families.size)
        val cunny = rows.first { it.name == "CuNNy 2×" }
        assertEquals(ModelCatalog.CUNNY_LEVELS, cunny.levelNames)
        assertEquals("cunny-8x32-ds-rp", cunny.models[cunny.firstLevel].id)
        assertTrue(cunny.sizes)
        val artcnn = rows.first { it.name == "ArtCNN 2×" }
        assertEquals(listOf("C4F16", "C4F32"), artcnn.levelNames)
        assertEquals(listOf("artcnn-c4f16-ds", "artcnn-c4f32-ds"), artcnn.models.map { it.id })
        assertEquals("CuNNy 2×, 4x16", PickerRows.shortName(ModelCatalog.byId("cunny-4x16-ds-rp")!!))
        val a = rows.first { it.models.firstOrNull()?.id == "anime4k-a-s" }
        assertEquals(listOf("S", "M", "L", "VL", "UL"), a.levelNames)
        assertTrue(a.sizes)
        assertEquals("Anime4K A (sharp) 2×", a.name)
        val denoise = rows.first { it.name == "waifu2x denoise 2×" }
        assertEquals(listOf("0", "1", "2", "3"), denoise.levelNames)
        assertFalse(denoise.sizes)
        // Every offered model on exactly one row.
        assertEquals(Models.offered(AiStep.UPSCALE, true, null).map { it.id }.sorted(), rows.flatMap { r -> r.models.map { it.id } }.sorted())
        // Sizes show the largest level's ratings at any level; strengths their own.
        assertEquals(ModelRatings.of(ShaderCatalog.byId("anime4k-a-ul")!!), a.ratings(0))
        assertTrue(denoise.ratings(1).isEmpty())
        assertEquals(ModelRatings.of(Models.byId("waifu2x-upconv7-2x-denoise3")!!), denoise.ratings(3))
    }

    @Test
    fun anImportedModelHasNoSpeedStars() {
        // Its time was measured on this device, not on the reference tablet the stars are scaled to.
        val mine = UserModel("u1", "Mine", AiStep.UPSCALE, 2, 1_500, 100, 1_000, "a".repeat(64), "b".repeat(64))
        assertNull(PickerRow("Mine", listOf(mine)).speed(0))
        val default = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
        assertEquals(ModelRatings.speed(default.pageMs), PickerRow("waifu2x", listOf(default)).speed(0))
    }

    @Test
    fun aRowSaysItsNameAndSpeedAndOffersMoreInformation() {
        val p = picker(AiStep.UPSCALE, current = ModelCatalog.DEFAULT_UPSCALE)
        val row = rowOf(p, "waifu2x 2× (default)")
        val main = row.findViewById<View>(R.id.ai_model_row)
        val halves = ModelRatings.speed(ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!.pageMs)!!
        assertEquals(halves, row.findViewById<StarsView>(R.id.ai_model_speed).halves)
        val stars = java.text.NumberFormat.getInstance().apply { maximumFractionDigits = 1 }.format(halves / 2.0)
        assertEquals("waifu2x 2× (default), Speed: $stars of 5 stars", main.contentDescription.toString())
        assertEquals("More information about waifu2x 2×", info(row).contentDescription.toString())
        // Read as a radio button, checked.
        val node = AccessibilityNodeInfo.obtain()
        main.onInitializeAccessibilityNodeInfo(node)
        assertTrue(node.isCheckable)
        assertTrue(node.isChecked)
        assertEquals(RadioButton::class.java.name, node.className)
        // Touch targets of 48 dp at least.
        val dp48 = (48 * context.resources.displayMetrics.density).roundToInt()
        assertTrue(main.minimumHeight >= dp48)
        assertTrue(info(row).layoutParams.width >= dp48 && info(row).layoutParams.height >= dp48)
        // None has no speed or details.
        val none = rows(p)[0]
        assertEquals(View.GONE, info(none).visibility)
        assertEquals(View.GONE, none.findViewById<View>(R.id.ai_model_speed_line).visibility)
    }

    @Test
    fun theInfoButtonShowsTheFullNameRatingsAndDescriptionThenHidesThem() {
        val p = picker(AiStep.UPSCALE)
        val row = rowOf(p, "waifu2x 2× (default)")
        assertEquals(View.GONE, details(row).visibility)
        info(row).performClick()
        assertEquals(View.VISIBLE, details(row).visibility)
        val m = ModelCatalog.byId(ModelCatalog.DEFAULT_UPSCALE)!!
        assertEquals("${m.name} · ${ModelText.size(context, m.bytes)}", row.findViewById<TextView>(R.id.ai_model_full_name).text.toString())
        assertEquals(
            listOf("Speed" to ModelRatings.speed(m.pageMs)!!, "Crisp" to 1, "Color" to 10, "Detail" to 10, "Desharp" to 4, "Lines" to 2),
            ratings(row)
        )
        assertEquals(ModelText.description(context, m), row.findViewById<TextView>(R.id.ai_model_description).text.toString())
        assertEquals("Hide information about waifu2x 2×", info(row).contentDescription.toString())
        // No pick from the info button.
        assertTrue(picks.isEmpty())
        info(row).performClick()
        assertEquals(View.GONE, details(row).visibility)
        assertEquals("More information about waifu2x 2×", info(row).contentDescription.toString())
    }

    @Test
    fun unratedModelsShowOnlyTheirSpeedAndSlowOnesSaySo() {
        val p = picker(AiStep.UPSCALE, keepSimple = false)
        val cunny = rowOf(p, "CuNNy 2×")
        info(cunny).performClick()
        assertEquals(listOf("Speed"), ratings(cunny).map { it.first })
        val lsdir = rowOf(p, "LSDIR 4×")
        info(lsdir).performClick()
        assertEquals(listOf("Speed", "Crisp", "Color", "Detail", "Desharp", "Lines"), ratings(lsdir).map { it.first })
        assertEquals(
            "Best for restoring heavily artifacted or blurry art; slow, may reimagine detail\nSlow (~14 s per page)",
            lsdir.findViewById<TextView>(R.id.ai_model_description).text.toString()
        )
        // A rating screen readers hear with what it means.
        val crisp = lsdir.findViewById<TableLayout>(R.id.ai_model_ratings).children.filterIsInstance<TableRow>().first { (it.getChildAt(0) as TextView).text == "Crisp" }
        assertEquals("Crisp: 4 of 5 stars. Makes blurry or artifacted art crisp", crisp.contentDescription.toString())
    }

    @Test
    fun aSizeFamilyOffersItsLargestLevelsRatingsUpTo() {
        val p = picker(AiStep.UPSCALE)
        val row = rowOf(p, "Anime4K A (sharp) 2×")
        info(row).performClick()
        val table = row.findViewById<TableLayout>(R.id.ai_model_ratings)
        val head = table.children.first { it !is TableRow } as TextView
        assertEquals("Offers up to, at UL:", head.text.toString())
        assertEquals(1, table.indexOfChild(head))
        val desharp = table.children.filterIsInstance<TableRow>().first { (it.getChildAt(0) as TextView).text == "Desharp" }
        assertEquals(ModelRatings.WORSE, ((desharp.getChildAt(1) as ViewGroup).getChildAt(0) as StarsView).halves)
        assertEquals(1, ((desharp.getChildAt(1) as ViewGroup).getChildAt(0) as StarsView).count)
        assertEquals("makes it worse", ((desharp.getChildAt(1) as ViewGroup).getChildAt(1) as TextView).text.toString())
        assertEquals("Desharp: makes it worse. Cleans up sharpening artifacts", desharp.contentDescription.toString())
        // Unpicked, its slider stands at the largest level.
        val slider = slider(row)
        assertEquals(View.VISIBLE, row.findViewById<View>(R.id.ai_model_level).visibility)
        assertEquals(4f, slider.value)
        assertEquals("UL", row.findViewById<TextView>(R.id.ai_model_level_name).text.toString())
        assertEquals("Level of Anime4K A (sharp) 2×", slider.contentDescription.toString())
    }

    @Test
    fun movingASliderPicksTheLevelAndKeepsThePickerOpen() {
        val p = picker(AiStep.UPSCALE, current = ModelCatalog.DEFAULT_UPSCALE)
        val row = rowOf(p, "Anime4K C (balanced) 2×")
        slide(row, 2)
        assertEquals("anime4k-c-l", p.picked)
        assertEquals("anime4k-c-l" to false, picks.last())
        assertTrue(checked(row))
        assertFalse(checked(rowOf(p, "waifu2x 2× (default)")))
        assertEquals("L", row.findViewById<TextView>(R.id.ai_model_level_name).text.toString())
        // The speed follows the level.
        assertEquals(ModelRatings.speed(ShaderCatalog.byId("anime4k-c-l")!!.pageMs), row.findViewById<StarsView>(R.id.ai_model_speed).halves)
        // A tap on the row picks the level shown and closes.
        tap(row)
        assertEquals("anime4k-c-l" to true, picks.last())
        // Opened on a picked level, the slider shows it.
        val again = rowOf(picker(AiStep.UPSCALE, current = "anime4k-c-m"), "Anime4K C (balanced) 2×")
        assertEquals(1f, slider(again).value)
        assertTrue(checked(again))
        // Strengths too.
        val denoise = rowOf(p, "waifu2x denoise 2×")
        assertEquals(3f, slider(denoise).value)
        slide(denoise, 0)
        assertEquals("waifu2x-upconv7-2x-denoise0", p.picked)
    }

    @Test
    fun aFamilyShowsItsLevelPickedLastWhileAnotherRowIsPicked() {
        val p = picker(AiStep.UPSCALE, current = ModelCatalog.DEFAULT_UPSCALE)
        slide(rowOf(p, "Anime4K A (sharp) 2×"), 1)
        tap(rowOf(p, "waifu2x 2× (default)"))
        picks.clear()
        // Opened again on waifu2x, Anime4K A's slider stands at M, picking nothing.
        val again = picker(AiStep.UPSCALE, current = ModelCatalog.DEFAULT_UPSCALE)
        val a = rowOf(again, "Anime4K A (sharp) 2×")
        assertEquals(1f, slider(a).value)
        assertEquals("M", a.findViewById<TextView>(R.id.ai_model_level_name).text.toString())
        assertFalse(checked(a))
        assertEquals(ModelCatalog.DEFAULT_UPSCALE, again.picked)
        rest()
        assertTrue(picks.isEmpty())
        // Families never picked show their largest level.
        assertEquals(4f, slider(rowOf(again, "Anime4K B (clean) 2×")).value)
        // A tap on the row picks the level shown.
        tap(a)
        assertEquals(listOf<Pair<String?, Boolean>>("anime4k-a-m" to true), picks)
        // A row tap is remembered at the level it picks (here the level picked as the picker opened).
        tap(rowOf(picker(AiStep.UPSCALE, current = "anime4k-a-l"), "Anime4K A (sharp) 2×"))
        assertEquals(2f, slider(rowOf(picker(AiStep.UPSCALE, current = null), "Anime4K A (sharp) 2×")).value)
        // Strengths alike.
        slide(rowOf(again, "waifu2x denoise 2×"), 1)
        tap(rowOf(again, "OpenComic 2×"))
        assertEquals(1f, slider(rowOf(picker(AiStep.UPSCALE, current = null), "waifu2x denoise 2×")).value)
    }

    @Test
    fun aRememberedLevelCountsOnlyWhileOfferedAndInItsOwnStep() {
        val group = PickerRows.of(AiStep.UPSCALE, true, null, "None").first { it.name == "Anime4K A (sharp) 2×" }.group
        val levels = PickerLevels(context)
        // A level no longer offered: the largest shows.
        levels.put(AiStep.UPSCALE, group, "gone")
        assertEquals(4f, slider(rowOf(picker(AiStep.UPSCALE), "Anime4K A (sharp) 2×")).value)
        levels.put(AiStep.UPSCALE, group, "anime4k-a-s")
        assertEquals(0f, slider(rowOf(picker(AiStep.UPSCALE), "Anime4K A (sharp) 2×")).value)
        assertNull(levels.get(AiStep.DESCREEN, group))
        // A picked level wins over the one remembered.
        assertEquals(2f, slider(rowOf(picker(AiStep.UPSCALE, current = "anime4k-a-l"), "Anime4K A (sharp) 2×")).value)
        // Kept out of backups.
        assertFalse(minty.emanata.util.Backup.export(app, deviceId = null).contains("anime4k-a-s"))
    }

    @Test
    fun keyPressesPickOnceTheSliderRests() {
        val p = picker(AiStep.UPSCALE, current = ModelCatalog.DEFAULT_UPSCALE)
        val row = rowOf(p, "Anime4K A (sharp) 2×")
        // UL to S: four steps, no pick while stepping.
        press(row, 0)
        assertTrue(picks.isEmpty())
        assertEquals("S", row.findViewById<TextView>(R.id.ai_model_level_name).text.toString())
        rest()
        assertEquals(listOf<Pair<String?, Boolean>>("anime4k-a-s" to false), picks)
        rest()
        assertEquals(1, picks.size)
        // Closing picks a level still settling, at once and once.
        press(row, 2)
        p.flush()
        assertEquals("anime4k-a-l" to false, picks.last())
        rest()
        assertEquals(2, picks.size)
        // A row tap drops a level still settling.
        press(row, 3)
        tap(rowOf(p, "waifu2x 2× (default)"))
        rest()
        assertEquals(listOf<Pair<String?, Boolean>>("anime4k-a-s" to false, "anime4k-a-l" to false, ModelCatalog.DEFAULT_UPSCALE to true), picks)
    }

    @Test
    fun aDragPicksOnceOnRelease() {
        val p = picker(AiStep.UPSCALE, current = ModelCatalog.DEFAULT_UPSCALE)
        val width = 1200
        p.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        // Shorter than its rows, so the list scrolls and the slider waits for a sideways move before it drags.
        p.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        p.view.layout(0, 0, width, 600)
        assertTrue(p.view.canScrollVertically(1))
        val row = rowOf(p, "Anime4K C (balanced) 2×")
        val s = slider(row)
        assertTrue(s.width > 0)
        fun x(level: Float) = s.trackSidePadding + level / s.valueTo * s.trackWidth
        val y = s.height / 2f
        val down = 0L
        var t = 0L
        fun send(action: Int, at: Float) {
            t += 16
            val e = MotionEvent.obtain(down, t, action, at, y, 0)
            s.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x(4f))
        for (level in listOf(3.6f, 3f, 2.4f, 2f, 1f, 1.1f)) send(MotionEvent.ACTION_MOVE, x(level))
        // Moving, it shows each level but picks none.
        assertEquals(1f, s.value)
        assertEquals("M", row.findViewById<TextView>(R.id.ai_model_level_name).text.toString())
        assertTrue(picks.isEmpty())
        send(MotionEvent.ACTION_UP, x(1f))
        assertEquals(listOf<Pair<String?, Boolean>>("anime4k-c-m" to false), picks)
        rest()
        assertEquals(1, picks.size)
        // A touch the scrolling list takes over before it drags picks nothing.
        send(MotionEvent.ACTION_DOWN, x(1f))
        send(MotionEvent.ACTION_MOVE, x(1f) + 1)
        send(MotionEvent.ACTION_CANCEL, x(1f) + 1)
        rest()
        assertEquals(1f, s.value)
        assertEquals(1, picks.size)
    }

    @Test
    fun openPanelsAndScrollCarryOverToARebuiltPicker() {
        val p = picker(AiStep.UPSCALE)
        info(rowOf(p, "Anime4K A (sharp) 2×")).performClick()
        info(rowOf(p, "CuNNy 2×")).performClick()
        info(rowOf(p, "CuNNy 2×")).performClick()
        info(rowOf(p, "OpenComic 2×")).performClick()
        assertEquals(listOf("opencomic-ai-upscale-2x-compact-v2", "anime4k-a-s"), p.expandedKeys.sortedDescending())
        val again = ModelPicker.of(context, AiStep.UPSCALE, true, null, p.expandedKeys, 0) { _, _ -> }
        assertEquals(View.VISIBLE, details(rowOf(again, "Anime4K A (sharp) 2×")).visibility)
        assertEquals(View.VISIBLE, details(rowOf(again, "OpenComic 2×")).visibility)
        assertEquals(View.GONE, details(rowOf(again, "CuNNy 2×")).visibility)
        assertEquals("Hide information about OpenComic 2×", info(rowOf(again, "OpenComic 2×")).contentDescription.toString())
    }

    @Test
    fun aRowComesIntoSightByTheLeastScroll() {
        // In sight: unmoved.
        assertEquals(100, ModelPicker.inSight(100, 150, 200, 300))
        assertEquals(100, ModelPicker.inSight(100, 100, 400, 300))
        // Below: its bottom at the bottom; above: its top at the top.
        assertEquals(150, ModelPicker.inSight(100, 400, 450, 300))
        assertEquals(40, ModelPicker.inSight(100, 40, 90, 300))
        // Taller than the window: its top at the top.
        assertEquals(500, ModelPicker.inSight(100, 500, 900, 300))
    }

    @Test
    fun tappingNonePicksNone() {
        val p = picker(AiStep.DESCREEN, current = "opencomic-ai-descreen-hard-compact-v2")
        assertTrue(checked(rowOf(p, "OpenComic v2")))
        tap(rows(p)[0])
        assertEquals(null to true, picks.single())
        assertNull(p.picked)
        assertTrue(checked(rows(p)[0]))
    }

    @Test
    fun rowsFitANarrowWindowAtLargeFontSizes() {
        val config = Configuration(app.resources.configuration).apply { fontScale = 2f }
        val big = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_Emanata)
        val p = picker(AiStep.UPSCALE, ctx = big)
        val width = (320 * big.resources.displayMetrics.density).roundToInt()
        val row = rowOf(p, "Anime4K Upscale + Denoise 2×")
        info(row).performClick()
        p.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        p.view.layout(0, 0, width, p.view.measuredHeight)
        val name = row.findViewById<TextView>(R.id.ai_model_name)
        val infoButton = info(row)
        val dp48 = (48 * big.resources.displayMetrics.density).roundToInt()
        // The name gives way rather than pushing the info button out.
        assertTrue(name.width in 1 until width - dp48)
        assertEquals(dp48, infoButton.width)
        assertTrue(infoButton.right <= width)
        assertTrue(row.findViewById<View>(R.id.ai_model_level_slider).width > 0)
        // Stars grow with the font (Android 14 on scales large sizes up less than small ones).
        val stars = row.findViewById<StarsView>(R.id.ai_model_speed)
        assertTrue(stars.measuredHeight >= (StarsView.STAR_SP * 1.5f * big.resources.displayMetrics.density).toInt())
    }

    companion object {
        /** The rows of the picker [dialog] shows. */
        fun rows(dialog: Dialog): List<View> = dialog.findViewById<ViewGroup>(R.id.ai_model_list).children.toList()

        fun name(row: View): String = row.findViewById<TextView>(R.id.ai_model_name).text.toString()

        fun checked(row: View): Boolean = row.findViewById<RadioButton>(R.id.ai_model_check).isChecked

        fun tap(row: View) {
            row.findViewById<View>(R.id.ai_model_row).performClick()
        }

        fun slider(row: View): Slider = row.findViewById(R.id.ai_model_level_slider)

        /** Moves [row]'s level slider to [level] with arrow keys, as a keyboard or switch user would, without letting it rest. */
        fun press(row: View, level: Int) {
            val s = slider(row)
            repeat(s.valueTo.roundToInt()) {
                if (s.value.roundToInt() == level) return
                val key = if (s.value < level) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                s.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key))
            }
            assertEquals(level, s.value.roundToInt())
        }

        /** [press], then lets the slider rest so its level is picked. */
        fun slide(row: View, level: Int) {
            press(row, level)
            rest()
        }

        fun rest() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ModelPicker.SETTLE_MS))
        }
    }
}
