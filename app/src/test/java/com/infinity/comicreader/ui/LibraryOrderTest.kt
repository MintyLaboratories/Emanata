package com.infinity.comicreader.ui

import com.infinity.comicreader.testing.toggleFavorite
import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SortKey
import com.infinity.comicreader.data.SortLevel
import com.infinity.comicreader.data.TabSort
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryOrderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun favoriteFoldersSortByWhatTheyHold() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        val older = File(tmp.root, "Series B").apply { mkdirs() }
        File(older, "B 01 (2001).cbz").createNewFile()
        val newer = File(tmp.root, "Series A").apply { mkdirs() }
        File(newer, "A 01 (2015).cbz").createNewFile()
        val library = Library(context)
        library.toggleFavorite(older)
        library.toggleFavorite(newer)
        val prefs = Prefs(context)
        prefs.groupFavorites = false
        prefs.setSort(Prefs.TAB_FAVORITES, TabSort(SortLevel(SortKey.YEAR, flipped = true)))

        // Oldest first, by the years of the books inside.
        assertEquals(
            listOf("Series B", "Series A"),
            LibraryOrder.favorites(context, prefs, emptyList()).map { it.name }
        )
    }
}
