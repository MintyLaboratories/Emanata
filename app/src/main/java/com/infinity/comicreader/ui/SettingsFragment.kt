package com.infinity.comicreader.ui

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import android.text.TextUtils
import android.text.format.Formatter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.R
import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LibraryData
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SettingsRepository
import com.infinity.comicreader.data.Thrift
import com.infinity.comicreader.data.StartupLocation
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareBackup
import com.infinity.comicreader.net.ShareStore
import com.infinity.comicreader.reader.AutoScroll
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
        setUpSections(prefs)
        setUpBattery()

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

        findPreference<Preference>(Prefs.KEY_NETWORK_TIMEOUT)?.setOnPreferenceChangeListener { _, value ->
            Remotes.answerMs = Prefs.timeoutMsOf(value as String)
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
                .setMessage(requireContext().statsText(st))
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

        findPreference<Preference>("pref_clear_cache")?.let { pref ->
            showCacheUsed(pref)
            pref.setOnPreferenceClickListener {
                askClearCache(pref)
                true
            }
        }

        findPreference<Preference>("pref_clear_library")?.setOnPreferenceClickListener {
            askClearLibrary(library)
            true
        }

        findPreference<Preference>("pref_reset")?.setOnPreferenceClickListener {
            confirm(requireContext(), R.string.confirm_reset_title, R.string.confirm_reset_msg, R.string.reset) {
                prefs.reset()
                RemoteCache.limitBytes = prefs.networkCacheBytes
                Remotes.answerMs = prefs.networkTimeoutMs
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                Locales.apply("system")
                toast(R.string.pref_reset_done)
                activity?.recreate()
            }
            true
        }
    }

    /**
     * Folds the sections left folded, gives advanced settings their look ([AdvancedSettings]),
     * and shows what Keep it simple and the folds leave, again as either changes.
     */
    private fun setUpSections(prefs: Prefs) {
        val screen = preferenceScreen
        val tertiary = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorTertiary, 0)
        val tag = getString(R.string.advanced_tag)
        val density = resources.displayMetrics.density
        val collapsed = prefs.collapsedSettings
        for (i in 0 until screen.preferenceCount) {
            val section = screen.getPreference(i) as? PreferenceGroup ?: continue
            if (section is CollapsibleCategory) {
                section.collapsed = section.key in collapsed
                section.onToggle = { s ->
                    prefs.collapsedSettings = if (s.collapsed) prefs.collapsedSettings + s.key else prefs.collapsedSettings - s.key
                    AdvancedSettings.apply(screen, prefs.keepSimple)
                }
            }
            for (j in 0 until section.preferenceCount) {
                val pref = section.getPreference(j)
                if (AdvancedSettings.isAdvanced(pref)) pref.title = AdvancedSettings.styled(pref.title ?: "", tag, tertiary, density)
            }
        }
        AdvancedSettings.apply(screen, prefs.keepSimple)
        findPreference<Preference>("pref_keep_simple")?.setOnPreferenceChangeListener { _, value ->
            AdvancedSettings.apply(screen, value as Boolean)
            true
        }
    }

    /** The space the cache takes now, measured off the main thread. */
    private suspend fun cacheUsed(): String {
        val app = requireContext().applicationContext
        val bytes = withContext(AppDispatchers.io) { BookCaches.bytes(app) }
        return Formatter.formatShortFileSize(app, bytes)
    }

    /** Shows on [pref] the space the cache takes. */
    private fun showCacheUsed(pref: Preference) {
        lifecycleScope.launch {
            val used = cacheUsed()
            if (isAdded) pref.summary = getString(R.string.pref_cache_used, used)
        }
    }

    /** Clears what the app can make again ([BookCaches]), once confirmed with the space it frees. */
    private fun askClearCache(pref: Preference) {
        lifecycleScope.launch {
            val used = cacheUsed()
            val context = context ?: return@launch
            confirm(context, R.string.confirm_clear_cache_title, getString(R.string.confirm_clear_cache_msg, used), R.string.clear) {
                val app = context.applicationContext
                lifecycleScope.launch {
                    withContext(AppDispatchers.io) { BookCaches.clear(app) }
                    toast(R.string.pref_cleared_cache)
                    if (isAdded) showCacheUsed(pref)
                }
            }
        }
    }

    /**
     * Clears what cannot be made again ([LibraryData.clear]) once "Delete them for good" is
     * ticked; "Export first" exports a backup instead.
     */
    private fun askClearLibrary(library: Library) {
        val context = requireContext()
        val extra = layoutInflater.inflate(R.layout.dialog_check, null)
        val sure = extra.findViewById<CheckBox>(R.id.dialog_check).apply { setText(R.string.confirm_clear_library_check) }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.confirm_clear_library_title)
            .setMessage(R.string.confirm_clear_library_msg)
            .setView(extra)
            .setPositiveButton(R.string.clear) { _, _ ->
                val app = context.applicationContext
                lifecycleScope.launch {
                    withContext(AppDispatchers.io) { LibraryData.clear(app, library) }
                    toast(R.string.pref_cleared_library)
                }
            }
            .setNeutralButton(R.string.confirm_clear_library_export) { _, _ -> askBackup() }
            .setNegativeButton(R.string.cancel, null)
            .showKeepingFullscreen()
        val clear = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        clear.isEnabled = false
        sure.setOnCheckedChangeListener { _, checked -> clear.isEnabled = checked }
    }

    /** Says whether battery saver is on, as it changes. */
    private fun setUpBattery() {
        val state = findPreference<Preference>("battery_saver_state") ?: return
        val settings = SettingsRepository.get(requireContext())
        fun show() {
            state.setSummary(if (Thrift.saving(requireContext())) R.string.battery_saver_on else R.string.battery_saver_off)
        }
        show()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { settings.reader.collect { show() } }
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
