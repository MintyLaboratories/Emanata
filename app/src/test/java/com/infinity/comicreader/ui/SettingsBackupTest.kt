package com.infinity.comicreader.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ApplicationProvider
import com.infinity.comicreader.R
import com.infinity.comicreader.net.AesGcmBox
import com.infinity.comicreader.net.SecretBox
import com.infinity.comicreader.net.Share
import com.infinity.comicreader.net.SharePath
import com.infinity.comicreader.net.ShareStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import javax.crypto.spec.SecretKeySpec

/** Exporting a backup with saved share passwords says what becomes of them first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsBackupTest {

    private lateinit var context: Context
    private lateinit var before: SecretBox

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        before = ShareStore.defaultBox
        ShareStore.defaultBox = AesGcmBox { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        context.getSharedPreferences("shares", Context.MODE_PRIVATE).edit().clear().commit()
        ShareStore(context).put(Share("a1b2c3", "Share A", SharePath("nas.local", 445, "books", ""), "user", "pw"))
    }

    @After
    fun tearDown() {
        ShareStore.defaultBox = before
    }

    /** Taps "Export backup" and waits for the dialog it opens. */
    private fun tapBackup(): AlertDialog {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
        val pref = fragment.findPreference<Preference>("pref_backup")!!
        pref.onPreferenceClickListener!!.onPreferenceClick(pref)
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.takeIf { it.isShowing }?.let { return it }
            Thread.sleep(10)
        }
        throw AssertionError("no dialog")
    }

    private fun AlertDialog.message() = findViewById<TextView>(android.R.id.message)!!.text.toString()

    @Test
    fun withADeviceIdPasswordsAreSealedOrLeftOutAsTicked() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "0123456789abcdef")
        val dialog = tapBackup()
        assertEquals(context.getString(R.string.backup_passwords_msg), dialog.message())
        val check = dialog.findViewById<TextView>(R.id.dialog_check)
        assertNotNull(check)
        assertEquals(View.VISIBLE, check!!.visibility)
    }

    @Test
    fun withoutADeviceIdTheBackupSaysPasswordsAreLeftOut() {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, null)
        val dialog = tapBackup()
        assertEquals(context.getString(R.string.backup_passwords_unsealable), dialog.message())
        assertNull(dialog.findViewById<View>(R.id.dialog_check))
    }
}
