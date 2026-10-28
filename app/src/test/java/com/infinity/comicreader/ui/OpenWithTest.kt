package com.infinity.comicreader.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.files.BookUris
import com.infinity.comicreader.util.Formats
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenWithTest {

    @Test
    fun opensEveryTypeItSharesBooksAs() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val names = (Formats.ZIP + Formats.RAR + Formats.SEVEN_ZIP + Formats.TAR + Formats.PDF + Formats.IMAGE).map { "book.$it" }
        for (name in names) {
            val type = BookUris.mimeOf(name)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://elsewhere/$name"), type)
            val found = context.packageManager.queryIntentActivities(view, 0).map { it.activityInfo.name }
            assertTrue("$name as $type: $found", ListActivity::class.java.name in found)
        }
    }
}
