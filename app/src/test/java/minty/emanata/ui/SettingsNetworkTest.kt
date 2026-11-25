package minty.emanata.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import minty.emanata.R
import minty.emanata.data.Prefs
import minty.emanata.net.NetPolicy
import minty.emanata.net.NetRules
import minty.emanata.net.NetSkip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Settings › Network: what is skipped on metered connections and slow shares, and the large-book warning. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsNetworkTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        NetPolicy.rules = NetRules()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settings(): PreferenceFragmentCompat {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        idle()
        return activity.supportFragmentManager.findFragmentById(R.id.settings_container) as PreferenceFragmentCompat
    }

    private fun ListView.tap(at: Int) {
        performItemClick(adapter.getView(at, null, this), at, adapter.getItemId(at))
        idle()
    }

    private fun ListView.ticked(): List<Boolean> = (0 until adapter.count).map { isItemChecked(it) }

    @Test
    fun theNetworkSectionHoldsTheNetworkSettings() {
        val fragment = settings()
        val section = fragment.findPreference<Preference>("section_network")!!
        for (key in listOf(
            Prefs.KEY_NETWORK_CACHE, Prefs.KEY_NETWORK_TIMEOUT, Prefs.KEY_SIMILAR_SHARES, Prefs.KEY_METERED_SKIP,
            Prefs.KEY_METERED_WARN, Prefs.KEY_METERED_WARN_MB, Prefs.KEY_SLOW_SKIP
        )) {
            assertEquals(key, section, fragment.findPreference<Preference>(key)!!.parent)
        }
        val size = fragment.findPreference<ListPreference>(Prefs.KEY_METERED_WARN_MB)!!
        assertEquals("100", size.value)
        assertEquals(Prefs.KEY_METERED_WARN, size.dependency)
        assertTrue(Prefs(context).meteredWarn)
        assertEquals(100, Prefs(context).meteredWarnMb)
    }

    @Test
    fun meteredSkipsEverythingOptionalByDefaultAndUntickingOneUnticksAny() {
        val fragment = settings()
        val pref = fragment.findPreference<Preference>(Prefs.KEY_METERED_SKIP)!!
        val any = context.getString(R.string.net_skip_any)
        assertEquals(context.getString(R.string.net_skip_explicit, any), pref.summary.toString())
        pref.performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val list = dialog.listView
        assertEquals(NetSkip.METERED_CHOICES.size, list.adapter.count)
        assertEquals(List(NetSkip.METERED_CHOICES.size) { true }, list.ticked())
        // Unticking hashing unticks Any too.
        list.tap(NetSkip.METERED_CHOICES.indexOf(NetSkip.HASHING))
        assertEquals(listOf(true, false, true, true, false), list.ticked())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        val kept = setOf(NetSkip.COVERS, NetSkip.READ_AHEAD, NetSkip.DOWNLOADS)
        assertEquals(kept, Prefs(context).meteredSkip)
        assertEquals(kept, NetPolicy.rules.metered)
        assertEquals(
            context.getString(R.string.net_skip_explicit, listOf(R.string.net_skip_covers, R.string.net_skip_read_ahead, R.string.net_skip_downloads).joinToString(", ") { context.getString(it) }),
            pref.summary.toString()
        )
    }

    @Test
    fun slowSharesOfferTheirOwnChoicesAndAnyTicksThemAll() {
        val fragment = settings()
        val pref = fragment.findPreference<Preference>(Prefs.KEY_SLOW_SKIP)!!
        pref.performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val list = dialog.listView
        assertEquals(NetSkip.SLOW_CHOICES.size, list.adapter.count)
        assertEquals(context.getString(R.string.net_skip_covers_slow), list.adapter.getItem(0).toString())
        assertEquals(listOf(true, true, true, false), list.ticked())
        list.tap(NetSkip.SLOW_CHOICES.indexOf(NetSkip.COVERS))
        list.tap(NetSkip.SLOW_CHOICES.indexOf(NetSkip.ANY))
        assertEquals(listOf(true, true, true, true), list.ticked())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(NetSkip.SLOW_CHOICES.toSet(), Prefs(context).slowSkip)
        assertFalse(NetSkip.DOWNLOADS in NetPolicy.rules.slow)
    }

    @Test
    fun cancelKeepsTheChoices() {
        val fragment = settings()
        fragment.findPreference<Preference>(Prefs.KEY_SLOW_SKIP)!!.performClick()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.listView.tap(0)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertEquals(NetRules.SLOW_DEFAULT, Prefs(context).slowSkip)
    }

    @Test
    fun nothingSkippedIsStoredAsNothing() {
        val prefs = Prefs(context)
        prefs.meteredSkip = emptySet()
        assertEquals(emptySet<NetSkip>(), prefs.meteredSkip)
        assertEquals(context.getString(R.string.net_skip_none), NetSkipDialog.summary(context, NetSkipDialog.Kind.METERED, emptySet()))
        // Backed up and restored like any other setting.
        val saved = prefs.exportAll()
        assertEquals("", saved[Prefs.KEY_METERED_SKIP])
    }
}
