package minty.emanata.ui

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
import minty.emanata.R
import minty.emanata.net.AesGcmBox
import minty.emanata.net.SecretBox
import minty.emanata.net.Share
import minty.emanata.net.SharePath
import minty.emanata.net.ShareStore
import minty.emanata.testing.idleUntil
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
        idleUntil("the backup dialog") { (ShadowDialog.getLatestDialog() as? AlertDialog)?.isShowing == true }
        return ShadowDialog.getLatestDialog() as AlertDialog
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
