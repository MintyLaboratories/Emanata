package minty.emanata.ui

import minty.emanata.ai.FileSet
import minty.emanata.ai.ModelStore
import minty.emanata.ai.Models
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PickerDownloadsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ids(p: PickerDownloads, last: String?, chosen: Set<String> = emptySet()) = p.passedOver(last, chosen).map { it.id }

    @Test
    fun levelsPassedOverAreDroppedTheLastKept() {
        val p = PickerDownloads { false }
        assertFalse(p.any)
        for (id in listOf("anime4k-a-s", "anime4k-a-m", "anime4k-a-l")) p.picked(id)
        assertTrue(p.any)
        assertEquals(listOf("anime4k-a-s", "anime4k-a-m"), ids(p, "anime4k-a-l"))
        // Back at the first: the others go.
        p.picked("anime4k-a-s")
        assertEquals(listOf("anime4k-a-m", "anime4k-a-l"), ids(p, "anime4k-a-s"))
    }

    @Test
    fun modelsInstalledBeforeOrChosenElsewhereStay() {
        val before = setOf("anime4k-a-s")
        val p = PickerDownloads { it.id in before }
        for (id in listOf("anime4k-a-s", "anime4k-a-m", "anime4k-a-l", "anime4k-a-vl")) p.picked(id)
        assertEquals(listOf("anime4k-a-l"), ids(p, "anime4k-a-vl", chosen = setOf("anime4k-a-m")))
    }

    @Test
    fun theStateWhenFirstPickedCounts() {
        // Installed by its own pick, a model passed over later still goes.
        var installed = false
        val p = PickerDownloads { installed }
        p.picked("anime4k-a-m")
        installed = true
        p.picked("anime4k-a-l")
        p.picked("anime4k-a-m")
        p.picked("anime4k-a-l")
        assertEquals(listOf("anime4k-a-m"), ids(p, "anime4k-a-l"))
    }

    @Test
    fun modelsPartlyDownloadedBeforeTheirPickStay() {
        val store = ModelStore(tmp.newFolder("models"))
        val m = Models.byId("anime4k-a-m") as FileSet
        store.dir(m).mkdirs()
        store.part(m, m.files.first()).writeBytes(ByteArray(8))
        val p = PickerDownloads.of(store)
        for (id in listOf("anime4k-a-s", "anime4k-a-m", "anime4k-a-l")) p.picked(id)
        assertEquals(listOf("anime4k-a-s"), ids(p, "anime4k-a-l"))
    }

    @Test
    fun noneAndUnknownIdsAreNotedButNothingToDrop() {
        val p = PickerDownloads { false }
        p.picked(null)
        p.picked("no-such-model")
        assertTrue(p.any)
        assertEquals(emptyList<String>(), ids(p, null))
    }
}
