package com.infinity.comicreader.ui

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import android.text.TextUtils
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.StartupLocation
import com.infinity.comicreader.data.Thumbs
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.ShareBackup
import com.infinity.comicreader.net.ShareStore
import com.infinity.comicreader.reader.AutoScroll
import com.infinity.comicreader.reader.PreviewStore
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.Backup
import com.infinity.comicreader.util.Locales
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class SettingsFragment : PreferenceFragmentCompat() {

    /** False when the backup being exported leaves share passwords out. */
    private var exportPasswords = true

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val app = requireContext().applicationContext
            val withPasswords = exportPasswords
            lifecycleScope.launch {
                // Sealing share passwords takes a moment.
                val ok = withContext(AppDispatchers.io) {
                    runCatching {
                        app.contentResolver.openOutputStream(uri, "wt")!!.use {
                            it.write(Backup.export(app, withPasswords).toByteArray(Charsets.UTF_8))
                        }
                    }.isSuccess
                }
                toast(if (ok) R.string.backup_done else R.string.op_failed)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportPasswords = savedInstanceState?.getBoolean(STATE_PASSWORDS, true) ?: true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PASSWORDS, exportPasswords)
    }

    /**
     * Exports a backup; when a share has a saved password, first offers "Without passwords"
     * (not ticked), saying where they can be opened again, or says they are left out when
     * this device has no ID to seal them with.
     */
    private fun askBackup() {
        val context = requireContext()
        lifecycleScope.launch {
            val saved = withContext(AppDispatchers.io) {
                runCatching { ShareStore(context).all().any { it.password.isNotEmpty() } }.getOrDefault(false)
            }
            if (!saved) {
                exportPasswords = true
                exportLauncher.launch(BACKUP_NAME)
                return@launch
            }
            if (ShareBackup.deviceId(context) == null) {
                AlertDialog.Builder(context)
                    .setTitle(R.string.pref_backup)
                    .setMessage(R.string.backup_passwords_unsealable)
                    .setPositiveButton(R.string.backup_export) { _, _ ->
                        exportPasswords = false
                        exportLauncher.launch(BACKUP_NAME)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return@launch
            }
            val extra = layoutInflater.inflate(R.layout.dialog_check, null)
            val without = extra.findViewById<CheckBox>(R.id.dialog_check).apply { setText(R.string.backup_without_passwords) }
            AlertDialog.Builder(context)
                .setTitle(R.string.pref_backup)
                .setMessage(R.string.backup_passwords_msg)
                .setView(extra)
                .setPositiveButton(R.string.backup_export) { _, _ ->
                    exportPasswords = !without.isChecked
                    exportLauncher.launch(BACKUP_NAME)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val app = requireContext().applicationContext
            lifecycleScope.launch {
                // Opening share passwords takes a moment, and closing their connections may wait on the network.
                val ok = withContext(NonCancellable + AppDispatchers.io) {
                    runCatching { readCapped(app, uri) }.getOrNull()?.let { text -> runCatching { Backup.import(app, text) }.getOrDefault(false) } ?: false
                }
                if (!ok) {
                    toast(R.string.restore_failed)
                    return@launch
                }
                // Theme and language come from the restored settings.
                val restored = Prefs(app)
                AppCompatDelegate.setDefaultNightMode(restored.nightMode)
                Locales.apply(restored.localeTag)
                toast(R.string.restore_done)
                activity?.recreate()
            }
        }

    /** Reads at most [Backup.MAX_BYTES] from [uri]; a larger file is not a backup. Blocking. */
    private fun readCapped(context: android.content.Context, uri: android.net.Uri): String? {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > Backup.MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }
        return null
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        val prefs = Prefs(requireContext())
        val library = Library(requireContext())

        findPreference<Preference>("pref_theme")?.setOnPreferenceChangeListener { _, value ->
            val mode = when (value as String) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)
            true
        }

        findPreference<Preference>("pref_locale")?.setOnPreferenceChangeListener { _, value ->
            Locales.apply(value as String)
            activity?.recreate()
            true
        }

        findPreference<ListPreference>("pref_startup_location")?.let { setUpStartupLocation(it, prefs) }

        findPreference<Preference>(Prefs.KEY_NETWORK_CACHE)?.setOnPreferenceChangeListener { _, value ->
            val bytes = ((value as String).toLongOrNull() ?: return@setOnPreferenceChangeListener false) shl 20
            RemoteCache.limitBytes = bytes
            val dir = RemoteCache.dirIn(requireContext().cacheDir)
            thread(name = "network-cache-trim", isDaemon = true) { RemoteCache.trim(dir, bytes) }
            true
        }

        findPreference<ListPreference>("pref_auto_scroll_speed")?.apply {
            entries = AutoScroll.SPEEDS.map { AutoScrollText.of(resources, it) }.toTypedArray()
            entryValues = AutoScroll.SPEEDS.map { AutoScroll.format(it) }.toTypedArray()
            value = AutoScroll.format(prefs.autoSpeed)
        }

        for (tab in Prefs.TABS) {
            val pref = findPreference<Preference>("view_$tab") ?: continue
            pref.summary = TabViewDialogs.summary(requireContext(), prefs, tab)
            pref.setOnPreferenceClickListener {
                TabViewDialogs.showTabView(requireContext(), prefs, tab) {
                    if (isAdded) pref.summary = TabViewDialogs.summary(requireContext(), prefs, tab)
                }
                true
            }
        }

        findPreference<Preference>("toolbar_buttons")?.let { pref ->
            pref.summary = ToolbarButtonsDialog.summary(requireContext(), prefs)
            pref.setOnPreferenceClickListener {
                ToolbarButtonsDialog.show(requireContext(), prefs) {
                    if (isAdded) pref.summary = ToolbarButtonsDialog.summary(requireContext(), prefs)
                }
                true
            }
        }

        findPreference<Preference>("pref_backup")?.setOnPreferenceClickListener {
            askBackup()
            true
        }

        findPreference<Preference>("pref_restore")?.setOnPreferenceClickListener {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.restore_confirm)
                .setPositiveButton(R.string.ok) { _, _ ->
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            true
        }

        findPreference<Preference>("pref_about")?.apply {
            val ctx = requireContext()
            val version = runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull().orEmpty()
            summary = getString(R.string.about_version_format, version)
            setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), AboutActivity::class.java))
                true
            }
        }

        findPreference<Preference>("pref_stats")?.setOnPreferenceClickListener {
            val st = library.stats()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.menu_stats)
                .setMessage(
                    getString(
                        R.string.stats_body, st.chapters, st.pages, st.seconds / 3600,
                        (st.seconds % 3600) / 60, st.daysRead, st.streak, st.bestStreak
                    )
                )
                .setPositiveButton(R.string.ok, null)
                .show()
            true
        }

        findPreference<Preference>("pref_clear_history")?.setOnPreferenceClickListener {
            confirm(requireContext(), R.string.confirm_clear_history_title, R.string.confirm_clear_history_msg, R.string.clear) {
                library.clearHistory()
                toast(R.string.pref_cleared)
            }
            true
        }

        findPreference<Preference>("pref_clear_bookmarks")?.setOnPreferenceClickListener {
            confirm(requireContext(), R.string.confirm_clear_bookmarks_title, R.string.confirm_clear_bookmarks_msg, R.string.clear) {
                library.clearBookmarks()
                toast(R.string.pref_cleared)
            }
            true
        }

        findPreference<Preference>("pref_clear_thumbs")?.setOnPreferenceClickListener {
            confirm(requireContext(), R.string.confirm_clear_thumbs_title, R.string.confirm_clear_thumbs_msg, R.string.clear) {
                val app = requireContext().applicationContext
                lifecycleScope.launch {
                    withContext(AppDispatchers.io) {
                        Thumbs.clear(app)
                        PreviewStore.clear(PreviewStore.dirIn(app.cacheDir))
                    }
                    toast(R.string.pref_cleared_thumbs)
                }
            }
            true
        }

        findPreference<Preference>("pref_reset")?.setOnPreferenceClickListener {
            confirm(requireContext(), R.string.confirm_reset_title, R.string.confirm_reset_msg, R.string.reset) {
                prefs.reset()
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                Locales.apply("system")
                toast(R.string.pref_reset_done)
                activity?.recreate()
            }
            true
        }
    }

    /** Shows the custom folder in the summary; choosing Custom asks for the folder first. */
    private fun setUpStartupLocation(pref: ListPreference, prefs: Prefs) {
        if (pref.value == null) pref.value = prefs.startupLocation
        val summary = Preference.SummaryProvider<ListPreference> { p ->
            val path = prefs.startupPath
            if (p.value == StartupLocation.CUSTOM && path != null) {
                TextUtils.expandTemplate(
                    getText(R.string.startup_custom_summary),
                    FolderPicker.describe(requireContext(), path)
                )
            } else {
                p.entry
            }
        }
        pref.summaryProvider = summary
        pref.setOnPreferenceChangeListener { _, value ->
            if (value != StartupLocation.CUSTOM) return@setOnPreferenceChangeListener true
            FolderPicker(requireContext(), lifecycleScope) { path ->
                prefs.setStartupFolder(path)
                pref.value = StartupLocation.CUSTOM
                // Same value, new folder: refresh the summary.
                pref.summaryProvider = summary
            }.show()
            false
        }
    }

    private fun toast(res: Int) {
        val context = context ?: return
        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val BACKUP_NAME = "emanata-backup.json"
        const val STATE_PASSWORDS = "export_passwords"
    }
}
