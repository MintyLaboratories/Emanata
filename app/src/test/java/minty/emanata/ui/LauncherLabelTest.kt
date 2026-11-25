package minty.emanata.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID = "http://schemas.android.com/apk/res/android"

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

    /** Robolectric ignores intent-filter labels, so this reads the manifest source. */
    @Test
    fun openWithChooserShowsTheAppName() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val filters = doc.getElementsByTagName("intent-filter")
        val labels = (0 until filters.length).map { filters.item(it) as Element }
            .filter { f ->
                val actions = f.getElementsByTagName("action")
                (0 until actions.length).any { (actions.item(it) as Element).getAttributeNS(ANDROID, "name") == Intent.ACTION_VIEW }
            }
            .map { it.getAttributeNS(ANDROID, "label") }
        assertEquals(listOf("@string/app_name"), labels)
    }

    @Test
    fun appKeepsTheAppName() {
        assertEquals(context.getString(R.string.app_name), context.applicationInfo.loadLabel(context.packageManager).toString())
    }
}
