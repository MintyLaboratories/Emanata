package com.infinity.comicreader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import android.text.TextUtils
import android.text.format.Formatter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SeekBarPreference
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiChain
import com.infinity.comicreader.ai.AiLoad
import com.infinity.comicreader.ai.AiModel
import com.infinity.comicreader.ai.AiPicks
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.ChainKind
import com.infinity.comicreader.ai.Downloads
import com.infinity.comicreader.ai.FileSet
import com.infinity.comicreader.ai.ModelDownloads
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.Models
import com.infinity.comicreader.ai.PageTimes
import com.infinity.comicreader.ai.UserModels
import com.infinity.comicreader.data.BookCaches
import com.infinity.comicreader.data.CropAnchor
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LibraryData
import com.infinity.comicreader.data.FabAction
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SettingsRepository
import com.infinity.comicreader.data.Thrift
import com.infinity.comicreader.data.StartupLocation
import com.infinity.comicreader.net.RemoteCache
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareBackup
import com.infinity.comicreader.net.ShareStore
import com.infinity.comicreader.reader.AutoScroll
import com.infinity.comicreader.reader.TallPages
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.Backup
import com.infinity.comicreader.util.Locales
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.concurrent.thread

class SettingsFragment : PreferenceFragmentCompat() {

    /** Shows the AI choices again ([setUpAi]). */
    private var refreshAi: () -> Unit = {}

    /** Lists each AI step's choices again, the slow models hidden while Keep it simple is on ([setUpAi]). */
    private var fillAi: (keepSimple: Boolean) -> Unit = {}

    /** False when the backup being exported leaves share passwords out. */
    private var exportPasswords = true

    /** The search's text while the search is open, else null. */
    private var query: String? = null

    /** Tints the setting a search result opened ([reveal]). */
    private val flash by lazy {
        SettingFlash(MaterialColors.getColor(requireContext(), androidx.appcompat.R.attr.colorPrimary, 0) and 0xFFFFFF or (FLASH_ALPHA shl 24))
    }

    private val results = SettingResults { key ->
        searchItem?.collapseActionView()
        reveal(key)
    }

    private var searchItem: MenuItem? = null
    private var resultsView: View? = null
    private var resultsEmpty: View? = null

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
        query = savedInstanceState?.getString(STATE_QUERY)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PASSWORDS, exportPasswords)
        outState.putString(STATE_QUERY, query)
    }

    /** The settings list, with search results over it ([showResults]). */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val list = super.onCreateView(inflater, container, savedInstanceState)
        return FrameLayout(inflater.context).apply {
            addView(list)
            addView(inflater.inflate(R.layout.settings_results, this, false))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.addItemDecoration(flash)
        resultsView = view.findViewById(R.id.settings_results)
        resultsEmpty = view.findViewById(R.id.settings_results_empty)
        view.findViewById<RecyclerView>(R.id.settings_results_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = results
        }
        requireActivity().addMenuProvider(menu, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        flash.stop()
        searchItem = null
        resultsView = null
        resultsEmpty = null
        super.onDestroyView()
    }

    /** Search, and the button that collapses every section while any is expanded, else expands every one. */
    private val menu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            inflater.inflate(R.menu.menu_settings, menu)
            val item = menu.findItem(R.id.settings_search)
            searchItem = item
            val search = item.actionView as SearchView
            search.queryHint = getString(R.string.settings_search_hint)
            search.maxWidth = Int.MAX_VALUE
            val open = query
            item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    if (query == null) query = ""
                    menu.findItem(R.id.settings_fold_all)?.isVisible = false
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    query = null
                    showResults()
                    activity?.invalidateMenu()
                    return true
                }
            })
            search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?): Boolean {
                    search.clearFocus()
                    return true
                }

                override fun onQueryTextChange(text: String?): Boolean {
                    if (query == null) return true
                    query = text.orEmpty()
                    showResults()
                    return true
                }
            })
            // The search open before the screen was made again.
            if (open != null) {
                item.expandActionView()
                search.setQuery(open, false)
            }
        }

        override fun onPrepareMenu(menu: Menu) {
            val expanded = SettingsFolds.anyExpanded(preferenceScreen)
            menu.findItem(R.id.settings_fold_all)?.apply {
                isVisible = query == null
                setTitle(if (expanded) R.string.settings_collapse_all else R.string.settings_expand_all)
                setIcon(if (expanded) R.drawable.ic_unfold_less else R.drawable.ic_unfold_more)
            }
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean {
            if (item.itemId != R.id.settings_fold_all) return false
            foldAll(SettingsFolds.anyExpanded(preferenceScreen))
            return true
        }
    }

    /** Lists the settings matching the search ([SettingsSearch]) over the settings, or nothing while it has no text. */
    private fun showResults() {
        val text = query.orEmpty()
        val found = if (text.isBlank()) emptyList() else SettingsSearch.find(SettingsSearch.index(preferenceScreen), text)
        results.entries = found
        resultsView?.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        resultsEmpty?.visibility = if (text.isNotBlank() && found.isEmpty()) View.VISIBLE else View.GONE
    }

    /** The search results listed now. */
    internal fun shownResults(): List<SettingEntry> = if (resultsView?.visibility == View.VISIBLE) results.entries else emptyList()

    /** Collapses every section when [collapse], else expands every one, and keeps that. */
    internal fun foldAll(collapse: Boolean) {
        val prefs = Prefs(requireContext())
        prefs.collapsedSettings = SettingsFolds.setAll(preferenceScreen, collapse)
        AdvancedSettings.apply(preferenceScreen, prefs.keepSimple)
        activity?.invalidateMenu()
    }

    /**
     * Shows the setting [key]: expands its section, shows it even while Keep it simple hides it
     * (until the sections are shown again; nothing is turned on), scrolls to it and tints it briefly.
     */
    internal fun reveal(key: String) {
        val pref = findPreference<Preference>(key) ?: return
        val prefs = Prefs(requireContext())
        val section = pref.parent as? CollapsibleCategory
        if (section != null && section.collapsed) {
            section.collapsed = false
            prefs.collapsedSettings = prefs.collapsedSettings - section.key
            AdvancedSettings.apply(preferenceScreen, prefs.keepSimple)
        }
        pref.isVisible = true
        activity?.invalidateMenu()
        val list = listView
        // The list takes the shown settings on its next pass.
        list.post {
            if (!isAdded) return@post
            val position = (list.adapter as? PreferenceGroup.PreferencePositionCallback)?.getPreferenceAdapterPosition(key) ?: RecyclerView.NO_POSITION
            val layout = list.layoutManager as? LinearLayoutManager
            if (position != RecyclerView.NO_POSITION && layout != null) layout.scrollToPositionWithOffset(position, list.height / 4)
            else scrollToPreference(key)
        }
        flash.flash(list, key)
    }

    /** The key of the setting tinted now ([reveal]), if any. */
    internal val flashing: String? get() = flash.key

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
        // No settings has an icon: titles start at the list's small start padding, on a phone as on a tablet.
        // Titles wrap: a long one, or a translation, shows in full on a narrow screen.
        fitRows(preferenceScreen)
        val prefs = Prefs(requireContext())
        val library = Library(requireContext())
        setUpSections(prefs)
        setUpBattery()
        setUpNetwork(prefs)
        setUpAi(prefs)

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

        // An unknown stored anchor shows as the one it reads as.
        findPreference<ListPreference>("pref_crop_anchor")?.apply {
            if (findIndexOfValue(value) < 0) value = CropAnchor.of(value).key
        }

        findPreference<ListPreference>("pref_startup_location")?.let { setUpStartupLocation(it, prefs) }
        findPreference<SeekBarPreference>(Prefs.KEY_TALL_THRESHOLD)?.let { setUpTallThreshold(it) }

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

        findPreference<ListPreference>(Prefs.KEY_FAB_TAP)?.apply {
            val (names, values) = RingOptionsDialog.tapChoices(requireContext())
            entries = names
            entryValues = values
            value = prefs.fabTap.key
        }

        findPreference<Preference>("view_ring")?.let { pref ->
            pref.summary = RingOptionsDialog.summary(requireContext(), prefs)
            pref.setOnPreferenceClickListener {
                RingOptionsDialog.show(requireContext(), prefs) {
                    if (isAdded) pref.summary = RingOptionsDialog.summary(requireContext(), prefs)
                }
                true
            }
        }

        findPreference<Preference>("more_actions")?.let { pref ->
            pref.summary = MoreActionsDialog.summary(requireContext(), prefs)
            pref.setOnPreferenceClickListener {
                MoreActionsDialog.show(requireContext(), prefs) {
                    if (isAdded) pref.summary = MoreActionsDialog.summary(requireContext(), prefs)
                }
                true
            }
        }

        findPreference<Preference>("page_actions")?.let { pref ->
            pref.summary = PageActionsDialog.summary(requireContext(), prefs)
            pref.setOnPreferenceClickListener {
                PageActionsDialog.show(requireContext(), prefs) {
                    if (isAdded) pref.summary = PageActionsDialog.summary(requireContext(), prefs)
                }
                true
            }
        }

        for ((key, choices) in listOf(Prefs.KEY_FAB_ONE to FabAction.FOR_ONE, Prefs.KEY_FAB_MANY to FabAction.FOR_MANY)) {
            findPreference<ListPreference>(key)?.apply {
                entries = choices.map { MoreActionsDialog.fabLabel(requireContext(), it) }.toTypedArray()
                entryValues = choices.map { it.key }.toTypedArray()
                value = (if (key == Prefs.KEY_FAB_ONE) prefs.fabOne else prefs.fabMany).key
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

        findPreference<Preference>("pref_clear_stats")?.setOnPreferenceClickListener {
            confirm(requireContext(), R.string.confirm_clear_stats_title, R.string.confirm_clear_stats_msg, R.string.clear) {
                library.clearStats()
                toast(R.string.pref_cleared)
            }
            true
        }

        findPreference<Preference>("pref_clear_favorites")?.setOnPreferenceClickListener {
            confirm(requireContext(), R.string.confirm_clear_favorites_title, R.string.confirm_clear_favorites_msg, R.string.clear) {
                library.clearFavorites()
                toast(R.string.pref_cleared)
            }
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

        findPreference<Preference>("pref_recalibrate")?.apply {
            summary = getString(R.string.pref_recalibrate_desc, CalibrationScreenActivity.control.estimateSeconds())
            setOnPreferenceClickListener {
                startActivity(CalibrationScreenActivity.intent(requireContext()))
                true
            }
        }

        findPreference<Preference>("pref_forget_missing")?.setOnPreferenceClickListener {
            startActivity(android.content.Intent(requireContext(), MissingBooksActivity::class.java))
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

    /** Keeps no room for an icon beside [pref] and every setting under it, and lets their titles wrap. */
    private fun fitRows(pref: Preference) {
        pref.isIconSpaceReserved = false
        pref.isSingleLineTitle = false
        if (pref is PreferenceGroup) for (i in 0 until pref.preferenceCount) fitRows(pref.getPreference(i))
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
                    activity?.invalidateMenu()
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
            fillAi(value)
            refreshAi()
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

    /** The skip choices for metered connections and slow shares, each a multiple choice showing what it skips. */
    private fun setUpNetwork(prefs: Prefs) {
        for (kind in NetSkipDialog.Kind.entries) {
            val key = if (kind == NetSkipDialog.Kind.METERED) Prefs.KEY_METERED_SKIP else Prefs.KEY_SLOW_SKIP
            val pref = findPreference<Preference>(key) ?: continue
            fun chosen() = if (kind == NetSkipDialog.Kind.METERED) prefs.meteredSkip else prefs.slowSkip
            pref.summary = NetSkipDialog.summary(requireContext(), kind, chosen())
            pref.setOnPreferenceClickListener {
                NetSkipDialog.show(requireContext(), kind, chosen()) { skips ->
                    if (kind == NetSkipDialog.Kind.METERED) prefs.meteredSkip = skips else prefs.slowSkip = skips
                    if (isAdded) pref.summary = NetSkipDialog.summary(requireContext(), kind, skips)
                }
                true
            }
        }
    }

    /** AI steps' pickers spaced for their multi-line choices ([AiChoiceDialog]); the rest as usual. */
    @Suppress("DEPRECATION") // setTargetFragment: how a preference dialog finds its preference.
    override fun onDisplayPreferenceDialog(preference: Preference) {
        val step = ChainKind.entries.firstNotNullOfOrNull { kind -> AiStep.entries.firstOrNull { Prefs.aiKey(it, kind) == preference.key } }
        if (preference !is ListPreference || step == null) {
            super.onDisplayPreferenceDialog(preference)
            return
        }
        if (parentFragmentManager.findFragmentByTag(AiChoiceDialog.TAG) != null) return
        AiChoiceDialog.of(preference.key, step).also { it.setTargetFragment(this, 0) }.show(parentFragmentManager, AiChoiceDialog.TAG)
    }

    /** A default chain's picker of one step, in Settings. */
    private data class AiPicker(val kind: ChainKind, val step: AiStep, val pref: ListPreference)

    /** The key of [kind]'s header ([AiChainPreference]). */
    private fun chainKey(kind: ChainKind) = "ai_chain_${kind.key}"

    /**
     * Fills each default chain's AI step choices (none, then the catalog's models with their
     * sizes and what they do, slow ones only while Keep it simple is off or when chosen, then
     * the imported ones, again as they come and go), starts downloading a catalog model as it
     * is chosen, as AI processing is turned on, or (when a download was cut short with the app)
     * as Settings open, and shows the chains run (hidden advanced choices too), each chain's
     * load estimate ([AiLoad]), each choice's download state and the space models take, as
     * they change.
     */
    private fun setUpAi(prefs: Prefs) {
        val context = requireContext()
        val store = ModelStore.of(context)
        val times = PageTimes.of(context)
        val pickers = ChainKind.entries.flatMap { kind ->
            AiStep.entries.mapNotNull { step -> findPreference<ListPreference>(Prefs.aiKey(step, kind))?.let { AiPicker(kind, step, it) } }
        }
        fun chosen(step: AiStep, value: Any?): AiModel? =
            (value as? String)?.let(Models::byId)?.takeIf { it.step == step }
        fun allChosen() = ChainKind.entries.flatMap { kind -> AiStep.entries.mapNotNull { chosen(it, prefs.aiModel(it, kind)) } }.distinct()
        fillAi = { keepSimple ->
            for ((_, step, pref) in pickers) {
                val (entries, values) = ModelText.choices(context, step, keepSimple, pref.value)
                pref.entries = entries.toTypedArray()
                pref.entryValues = values.toTypedArray()
                // A model no longer there (dropped from the catalog, or an imported one deleted) reads as none.
                if (pref.findIndexOfValue(pref.value) < 0) pref.value = Prefs.AI_NONE
            }
        }
        for ((kind, step, pref) in pickers) {
            // Unchosen, a step shows its default without storing it, so a later default reaches it.
            if (pref.value == null) {
                pref.isPersistent = false
                pref.value = prefs.aiModel(step, kind) ?: Prefs.AI_NONE
                pref.isPersistent = true
            }
            pref.setOnPreferenceChangeListener { _, value ->
                // Picking the shown default stores it: ListPreference skips an unchanged value.
                if (value == pref.value) prefs.setAiModel(step, value as String, kind)
                val settling = (parentFragmentManager.findFragmentByTag(AiChoiceDialog.TAG) as? AiChoiceDialog)?.settling == true
                if (prefs.aiEnabled && !settling) (chosen(step, value) as? FileSet)?.let { ModelDownloads.start(store, it) }
                refreshAi()
                true
            }
        }
        fillAi(prefs.keepSimple)
        val ai = findPreference<Preference>(Prefs.KEY_AI)
        ai?.setOnPreferenceChangeListener { _, value ->
            if (value == true) for (m in allChosen()) (m as? FileSet)?.let { ModelDownloads.start(store, it) }
            refreshAi()
            true
        }
        val manager = findPreference<Preference>("ai_models")
        manager?.setOnPreferenceClickListener {
            startActivity(android.content.Intent(context, ModelsActivity::class.java))
            true
        }
        var shownImported = UserModels.all
        suspend fun show(d: Downloads) {
            // Imported models came or went: the pickers list them.
            if (UserModels.all != shownImported) {
                shownImported = UserModels.all
                fillAi(prefs.keepSimple)
            }
            val installed = withContext(AppDispatchers.io) { Models.all().filter { store.installed(it) } }
            val used = withContext(AppDispatchers.io) { store.bytesUsed() }
            // Each chain's load estimate, of the models it runs whether AI processing is on or not.
            val loads = withContext(AppDispatchers.io) {
                ChainKind.entries.associateWith { AiLoad.pageMs(AiPicks.of(prefs, it).models, times::estimate) }
            }
            if (!isAdded) return
            for ((kind, ms) in loads) findPreference<AiChainPreference>(chainKey(kind))?.pageMs = ms
            // Keep it simple hides the size slider: sizes set away from the defaults show here instead.
            val sizes = if (prefs.keepSimple) AiSizeText.changed(context, prefs.aiThresholds) else null
            val chains = ChainKind.entries.associateWith { AiChain.chosen(prefs, it) }
            ai?.summary = if (prefs.aiEnabled) listOfNotNull(ModelText.chains(context, chains, loads), sizes).joinToString("\n")
            else getString(R.string.pref_ai_desc)
            manager?.summary = getString(R.string.ai_models_summary, installed.size, ModelText.size(context, used))
            for ((_, step, pref) in pickers) {
                val m = chosen(step, pref.value)
                val text = ModelText.choice(context, m, m != null && m in installed, m?.let { d.states[it.id] })
                // A provider, not a summary: ListPreference formats a summary, and "40%" is no format.
                pref.summaryProvider = Preference.SummaryProvider<ListPreference> { text }
            }
        }
        // A choice shows once stored, after its listener: the refresh waits for the disk first.
        refreshAi = { lifecycleScope.launch { show(ModelDownloads.state.value) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { ModelDownloads.state.collect { show(it) } }
        }
        // A failed download waits to be chosen again; one the app's end cut short goes on, unless on a metered connection.
        lifecycleScope.launch(AppDispatchers.io) {
            val state = ModelDownloads.state.value
            val chosen = if (prefs.aiEnabled) allChosen() else emptyList()
            for (m in chosen) if (m is FileSet && ModelDownloads.mayStartBySelf(state, m.id)) ModelDownloads.start(store, m, optional = true)
        }
    }

    /** Shows the threshold and the page shape it makes tall on this screen. */
    private fun setUpTallThreshold(pref: SeekBarPreference) {
        val aspect = ScreenShape.portraitAspect(requireContext())
        pref.summaryProvider = Preference.SummaryProvider<SeekBarPreference> { p ->
            getString(
                R.string.pref_tall_threshold_summary,
                String.format(Locale.getDefault(), "%.2f", p.value / 100.0),
                String.format(Locale.getDefault(), "%.2f", TallPages.minAspect(p.value, aspect))
            )
        }
    }

    /** Shows the custom folder in the summary; choosing Custom asks for the folder first. */
    private fun setUpStartupLocation(pref: ListPreference, prefs: Prefs) {
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
        const val STATE_QUERY = "search_query"

        /** The tint's opacity over a setting a search result opened. */
        const val FLASH_ALPHA = 0x3D
    }
}
