package com.infinity.comicreader.ui

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherLabelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The release strings; unit tests resolve the debug overlay, where both names are equal. */
    private val releaseStrings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    @Test
    fun releaseLauncherNameIsShort() {
        assertEquals("Emanata", releaseStrings["launcher_name"])
    }

    @Test
    fun releaseAppNameIsFull() {
        assertEquals("Emanata Comic Reader", releaseStrings["app_name"])
    }

    @Test
    fun launcherShowsTheLauncherName() {
        val pm = context.packageManager
        val launcher = pm.getActivityInfo(ComponentName(context, ListActivity::class.java), 0)
        assertEquals(context.getString(R.string.launcher_name), launcher.loadLabel(pm).toString())
    }

    @Test
    fun appKeepsTheAppName() {
        assertEquals(context.getString(R.string.app_name), context.applicationInfo.loadLabel(context.packageManager).toString())
    }
}
