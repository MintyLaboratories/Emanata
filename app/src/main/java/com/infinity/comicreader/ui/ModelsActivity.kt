package com.infinity.comicreader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiChain
import com.infinity.comicreader.ai.AiLoad
import com.infinity.comicreader.ai.AiModel
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.CatalogModel
import com.infinity.comicreader.ai.ChainKind
import com.infinity.comicreader.ai.DetectorModel
import com.infinity.comicreader.ai.DownloadFailure
import com.infinity.comicreader.ai.DownloadState
import com.infinity.comicreader.ai.Downloads
import com.infinity.comicreader.ai.FileSet
import com.infinity.comicreader.ai.ImportFailure
import com.infinity.comicreader.ai.ImportState
import com.infinity.comicreader.ai.ImportWarning
import com.infinity.comicreader.ai.ModelBlurb
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelCheck
import com.infinity.comicreader.ai.ModelDownloads
import com.infinity.comicreader.ai.ModelImports
import com.infinity.comicreader.ai.ModelRefusal
import com.infinity.comicreader.ai.ModelSource
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.ai.PreviewPicture
import com.infinity.comicreader.ai.Models
import com.infinity.comicreader.ai.ReaderAi
import com.infinity.comicreader.ai.ShaderModel
import com.infinity.comicreader.ai.UserModel
import com.infinity.comicreader.ai.UserModels
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Words for models, catalog and imported, shared by the model manager, Settings and the reader. */
object ModelText {
    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun step(context: Context, step: AiStep): String = context.getString(
        when (step) {
            AiStep.ARTIFACTS -> R.string.ai_step_artifacts
            AiStep.DESCREEN -> R.string.ai_step_descreen
            AiStep.UPSCALE -> R.string.ai_step_upscale
        }
    )

    /** Scale, size and license; for an imported model, that it is imported instead of a license. */
    fun details(context: Context, m: AiModel): String = when (m) {
        is CatalogModel -> context.getString(R.string.ai_model_details, m.scale, size(context, m.bytes), m.source.license)
        is ShaderModel -> context.getString(R.string.ai_model_details, m.scale, size(context, m.bytes), m.source.license)
        is UserModel -> context.getString(R.string.ai_model_details_imported, m.scale, size(context, m.bytes))
    }

    /** What a model manager's row says of [m] before it is tapped: its scale, and for an imported model that it is imported. */
    fun scale(context: Context, m: AiModel): String =
        if (m is UserModel) context.getString(R.string.ai_model_scale_imported, m.scale) else context.getString(R.string.ai_model_scale, m.scale)

    /** Everything known of [m]: its details and attribution ([attribution]), or for an imported model [imported]. */
    fun info(context: Context, m: AiModel): String = when (m) {
        is CatalogModel -> attribution(context, m, m.source)
        is ShaderModel -> attribution(context, m, m.source)
        is UserModel -> imported(context, m)
    }

    /**
     * What [m] does ([CatalogModel.blurb], [ShaderModel.blurb]), marked when it is the default,
     * then a line saying how slow it is when it is ([AiModel.slow]); empty for neither.
     */
    fun blurb(context: Context, m: AiModel): String {
        val lines = ArrayList<String>(2)
        description(context, m)?.let { text ->
            lines += if (m.id == ModelCatalog.DEFAULT_UPSCALE) context.getString(R.string.ai_model_default, text) else text
        }
        if (m.slow) lines += context.getString(R.string.ai_model_slow, m.pageSeconds)
        return lines.joinToString("\n")
    }

    /** What [m] does, best fit first, then its biggest downside; null where unknown (an imported model). */
    fun description(context: Context, m: AiModel): String? = when (m) {
        is CatalogModel -> m.blurb
        is ShaderModel -> m.blurb
        is UserModel -> null
    }?.let { context.getString(blurbText(it)) }

    private fun blurbText(b: ModelBlurb): Int = when (b) {
        ModelBlurb.WAIFU2X -> R.string.ai_blurb_waifu2x
        ModelBlurb.WAIFU2X_DENOISE -> R.string.ai_blurb_waifu2x_denoise
        ModelBlurb.OPENCOMIC_UPSCALE -> R.string.ai_blurb_opencomic_upscale
        ModelBlurb.REAL_ESRGAN_ANIME -> R.string.ai_blurb_real_esrgan_anime
        ModelBlurb.DESCREEN_V2 -> R.string.ai_blurb_descreen_v2
        ModelBlurb.DESCREEN_V3 -> R.string.ai_blurb_descreen_v3
        ModelBlurb.ARTIFACT_REMOVAL -> R.string.ai_blurb_artifact_removal
        ModelBlurb.LSDIR -> R.string.ai_blurb_lsdir
        ModelBlurb.CUNNY -> R.string.ai_blurb_cunny
        ModelBlurb.ARTCNN -> R.string.ai_blurb_artcnn
        ModelBlurb.ANIME4K_A -> R.string.ai_blurb_anime4k_a
        ModelBlurb.ANIME4K_B -> R.string.ai_blurb_anime4k_b
        ModelBlurb.ANIME4K_C -> R.string.ai_blurb_anime4k_c
        ModelBlurb.ANIME4K_UPSCALE -> R.string.ai_blurb_anime4k_upscale
        ModelBlurb.ANIME4K_UPSCALE_DENOISE -> R.string.ai_blurb_anime4k_upscale_denoise
    }

    /**
     * A picker's choices for [step]: none, then the models it offers ([Models.offered]), every
     * level of a family, with short names ([PickerRows.shortName]) and their ids (none's [Prefs.AI_NONE]).
     */
    fun choices(context: Context, step: AiStep, keepSimple: Boolean, current: String?): Pair<List<CharSequence>, List<String>> {
        val models = Models.offered(step, keepSimple, current)
        return (listOf<CharSequence>(context.getString(R.string.ai_none)) + models.map { PickerRows.shortName(it) }) to
            (listOf(Prefs.AI_NONE) + models.map { it.id })
    }

    fun failure(context: Context, f: DownloadFailure): String = context.getString(
        when (f) {
            DownloadFailure.NETWORK -> R.string.ai_download_failed_network
            DownloadFailure.SERVER -> R.string.ai_download_failed_server
            DownloadFailure.MISMATCH -> R.string.ai_download_failed_mismatch
            DownloadFailure.SPACE -> R.string.ai_download_failed_space
            DownloadFailure.METERED -> R.string.ai_download_failed_metered
        }
    )

    /** A step's choice [m] (null: none), [installed] or in download [state]. */
    fun choice(context: Context, m: AiModel?, installed: Boolean, state: DownloadState?): String = when {
        m == null -> context.getString(R.string.ai_none)
        installed -> m.name
        state is DownloadState.Running -> context.getString(R.string.ai_model_downloading, m.name, state.percent)
        state is DownloadState.Failed && state.failure == DownloadFailure.METERED -> context.getString(R.string.ai_model_metered, m.name)
        state is DownloadState.Failed -> context.getString(R.string.ai_model_failed, m.name)
        else -> context.getString(R.string.ai_model_missing, m.name)
    }

    /** The name of [kind]'s default chain. */
    fun kind(context: Context, kind: ChainKind): String =
        context.getString(if (kind == ChainKind.MANGA) R.string.ai_chain_manga else R.string.ai_chain_other)

    /**
     * AI processing's description, then the models each default chain of [chains] runs, in
     * order, and a warning when together one's models would run past a page's time
     * ([AiChain.slow]) by its [loads] (its load bar's page time; else its catalog times).
     */
    fun chains(
        context: Context,
        chains: Map<ChainKind, List<AiModel>>,
        loads: Map<ChainKind, Long> = chains.mapValues { AiLoad.pageMs(it.value) }
    ): String {
        val lines = listOf(context.getString(R.string.pref_ai_desc)) + chains.map { (kind, chain) ->
            val names = if (chain.isEmpty()) context.getString(R.string.ai_none) else chain.joinToString(" → ") { it.name }
            context.getString(R.string.ai_chain_of, kind(context, kind), names)
        }
        return (if (loads.values.any(AiChain::slow)) lines + context.getString(R.string.ai_chain_slow) else lines).joinToString("\n")
    }

    /** Who made [m], from [source], its license, where it is from and where it downloads from; then, for a conversion made for the app, what it is. */
    fun attribution(context: Context, m: AiModel, source: ModelSource): String {
        val info = context.getString(R.string.ai_model_info, details(context, m), source.author, source.license, source.url, source.repository)
        return source.conversion?.let { "$info\n\n$it" } ?: info
    }

    /** What is known of imported model [m]: its details, that it is the user's, and its time here. */
    fun imported(context: Context, m: UserModel): String =
        context.getString(R.string.ai_model_info_imported, details(context, m), m.pageSeconds)

    /**
     * The model manager's row of the cover text detector: its name, what it is, whether and why it
     * is in use; its model's download [progress] (percent) while one runs, and whether it offers
     * to [download] the model.
     */
    data class CoverTextRow(val title: String, val details: String, val status: String, val progress: Int? = null, val download: Boolean = false)

    /** PP-OCR's model: [installed], else its download's [state] (null: none asked for yet). */
    data class DetectorFiles(val installed: Boolean, val state: DownloadState? = null)

    /** The row of the cover text detector, PP-OCR, its model as [files] tell (null: not known yet, so no status and no button). */
    fun coverText(context: Context, files: DetectorFiles? = DetectorFiles(true)): CoverTextRow {
        val title = context.getString(R.string.cover_text_ppocr)
        val details = context.getString(R.string.cover_text_details_ppocr)
        if (files == null) return CoverTextRow(title, details, "")
        if (files.installed) return CoverTextRow(title, details, context.getString(R.string.cover_text_in_use))
        return when (val d = files.state) {
            is DownloadState.Running -> CoverTextRow(title, details, context.getString(R.string.cover_text_ppocr_downloading, d.percent), progress = d.percent)
            is DownloadState.Failed -> CoverTextRow(title, details, context.getString(R.string.cover_text_ppocr_failed, failure(context, d.failure)), download = true)
            null -> CoverTextRow(
                title, details,
                context.getString(R.string.cover_text_ppocr_missing, size(context, ModelCatalog.TEXT_DETECTOR.bytes)), download = true
            )
        }
    }

    /** Why an import was refused, in a sentence; a page time rounded up, so one just past a limit never reads as at it. */
    fun refusal(context: Context, r: ImportState.Refused): String = when (r.why) {
        ModelRefusal.RUNTIME -> context.getString(R.string.ai_refused_runtime)
        ModelRefusal.NOT_LOADED -> context.getString(R.string.ai_refused_not_loaded)
        ModelRefusal.WHOLE_TILE -> context.getString(R.string.ai_refused_whole_tile)
        ModelRefusal.DOES_NOT_RUN -> context.getString(R.string.ai_refused_does_not_run)
        ModelRefusal.SHAPE -> context.getString(R.string.ai_refused_shape)
        ModelRefusal.REACH -> context.getString(R.string.ai_refused_reach)
        ModelRefusal.MEMORY ->
            if (r.bytes > 0 && r.limit > 0) context.getString(R.string.ai_refused_memory, size(context, r.bytes), size(context, r.limit))
            else context.getString(R.string.ai_refused_memory_unknown)
        ModelRefusal.TOO_SLOW ->
            if (r.pageMs > 0) context.getString(R.string.ai_refused_too_slow, (r.pageMs + 999) / 1000, ReaderAi.MAX_TIMEOUT_MS / 1000)
            else context.getString(R.string.ai_refused_too_slow_check, (ModelCheck.TIME_LIMIT_MS / 1000))
    }

    /**
     * Fit model [s]'s page time in whole seconds: rounded up when warned of as slow, so one
     * just past the limit never reads as at it, and the naming after says the same; else to the nearest.
     */
    fun pageSeconds(s: ImportState.Fit): Long = if (ImportWarning.SLOW in s.warnings) (s.pageMs + 999) / 1000 else (s.pageMs + 500) / 1000

    /** What the user weighs before importing fit model [s] anyway: a paragraph per warning. */
    fun warnings(context: Context, s: ImportState.Fit): String = s.warnings.sorted().joinToString("\n\n") { w ->
        when (w) {
            ImportWarning.SLOW -> context.getString(R.string.ai_import_warn_slow, pageSeconds(s), ModelCheck.WARN_PAGE_MS / 1000)
        }
    }

    /** What test picture [p] of an import's before and after shows. */
    fun previewPicture(context: Context, p: PreviewPicture): String = context.getString(
        when (p) {
            PreviewPicture.GRADIENTS -> R.string.ai_import_preview_gradients
            PreviewPicture.JPEG -> R.string.ai_import_preview_jpeg
            PreviewPicture.DOTS -> R.string.ai_import_preview_dots
        }
    )

    /** Why picked files were not taken in, in a sentence. */
    fun failure(context: Context, f: ImportFailure): String = when (f) {
        ImportFailure.NOT_A_PAIR -> context.getString(R.string.ai_import_failed_pair)
        ImportFailure.UNREADABLE -> context.getString(R.string.ai_import_failed_unreadable)
        ImportFailure.TOO_LARGE ->
            context.getString(R.string.ai_import_failed_large, size(context, ModelImports.MAX_PARAM_BYTES), size(context, ModelImports.MAX_BIN_BYTES))
        ImportFailure.SPACE -> context.getString(R.string.ai_import_failed_space)
        ImportFailure.STORAGE -> context.getString(R.string.ai_import_failed_storage)
    }
}

/**
 * How an import's before and after show, in screen pixels: at the same [side], the before zoomed
 * [ZOOM] times, or to its after's size when that is larger (an upscaler's), the after never
 * shrunk; a window narrower than that shows both fitted to it.
 */
object ImportPreviewZoom {
    const val ZOOM = 2

    /** The side [before] and [after] both show at. */
    fun side(before: Bitmap, after: Bitmap): Int = maxOf(before.width * ZOOM, before.height * ZOOM, after.width, after.height)

    /** [b] at [side] x [side] ([side]), each pixel repeated, not blended, so artifacts show as they are; [b] itself at that size. */
    fun shown(b: Bitmap, side: Int): Bitmap {
        require(side >= b.width && side >= b.height) { "${b.width}x${b.height} would shrink to $side" }
        return if (b.width == side && b.height == side) b else Bitmap.createScaledBitmap(b, side, side, false)
    }
}

/**
 * The AI model manager: every model by step. Catalog models with what they do and their scale,
 * downloaded or not; downloads (with progress, resumed when cut short), cancels and
 * deletes. With Keep it simple on, slow catalog models show only once downloaded or
 * downloading. Imported models (marked so, no license) after them, deleted after asking.
 * "Import a model…" takes the user's own: its two files picked ([ModelImports]), copied and
 * checked with progress, then shown before and after on test pictures, then named and given a
 * step, or refused with the reason; a slow model goes on only once the user picks "Import
 * anyway" (Cancel, or Back, drops it). A
 * tap on a model's row shows what is known of it under it (its size, license and source), as
 * a picker's info button does ([ModelPicker]); another tap hides it.
 */
class ModelsActivity : AppCompatActivity() {
    private lateinit var store: ModelStore
    private lateinit var prefs: Prefs
    private val rows = HashMap<String, View>()

    /** The ids of the models whose rows show what is known of them. */
    private val opened = HashSet<String>()

    /** The cover text detector's row ([showCoverText]). */
    private var coverTextRow: View? = null

    /** PP-OCR's model as [show] last found it; null before. */
    private var detectorFiles: ModelText.DetectorFiles? = null

    /** The imported models the rows were built for. */
    private var builtFor: List<UserModel>? = null

    /** The import state shown, and its dialog. */
    private var shownImport: ImportState? = null
    private var importDialog: AlertDialog? = null

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        when {
            uris.isNotEmpty() -> ModelImports.start(this, uris)
            // Asked for the pair's other file and picked none.
            ModelImports.state.value is ImportState.Half -> ModelImports.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)
        val toolbar = findViewById<MaterialToolbar>(R.id.models_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        store = ModelStore.of(this)
        prefs = Prefs(this)
        savedInstanceState?.getStringArrayList(OPENED)?.let(opened::addAll)
        findViewById<MaterialButton>(R.id.models_import).setOnClickListener { pickFiles() }
        build()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ModelDownloads.state.collect { show(it) } }
                launch { ModelImports.state.collect { showImport(it) } }
            }
        }
        lifecycleScope.launch {
            val name = withContext(AppDispatchers.io) { ModelImports.crashed(this@ModelsActivity) } ?: return@launch
            AlertDialog.Builder(this@ModelsActivity)
                .setTitle(R.string.ai_import_failed)
                .setMessage(getString(R.string.ai_import_crashed, name))
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(OPENED, ArrayList(opened))
    }

    override fun onDestroy() {
        importDialog?.dismiss()
        importDialog = null
        super.onDestroy()
    }

    private fun pickFiles() {
        try {
            pick.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** A card of rows per step: its catalog models, then the imported ones ([Models.forStep]). */
    private fun build() {
        val list = findViewById<LinearLayout>(R.id.models_list)
        val header = list.indexOfChild(findViewById(R.id.models_import_hint)) + 1
        while (list.childCount > header) list.removeViewAt(header)
        rows.clear()
        builtFor = UserModels.all
        for (step in AiStep.entries) {
            val models = Models.forStep(step)
            if (models.isEmpty()) continue
            val title = layoutInflater.inflate(R.layout.item_section_title, list, false) as TextView
            title.text = ModelText.step(this, step)
            list.addView(title)
            val card = layoutInflater.inflate(R.layout.card_models, list, false) as MaterialCardView
            val rowsBox = card.findViewById<LinearLayout>(R.id.card_rows)
            for (m in models) {
                val row = layoutInflater.inflate(R.layout.item_model, rowsBox, false)
                row.findViewById<TextView>(R.id.title).text = m.name
                row.findViewById<TextView>(R.id.blurb).apply {
                    text = ModelText.blurb(this@ModelsActivity, m)
                    isVisible = text.isNotEmpty()
                }
                row.findViewById<TextView>(R.id.subtitle).text = ModelText.scale(this, m)
                row.findViewById<TextView>(R.id.model_info_text).text = ModelText.info(this, m)
                row.findViewById<MaterialButton>(R.id.model_info_link).apply {
                    val source = (m as? CatalogModel)?.source ?: (m as? ShaderModel)?.source
                    isVisible = source != null
                    if (source != null) setOnClickListener { open(source.url) }
                }
                // Shown once the store says whether it is downloaded.
                row.isVisible = m is UserModel || !m.slow
                row.setOnClickListener {
                    if (!opened.remove(m.id)) opened += m.id
                    bindInfo(row, m)
                }
                bindInfo(row, m)
                rowsBox.addView(row)
                rows[m.id] = row
            }
            list.addView(card)
        }
        addCoverText(list)
    }

    /** A card with the cover text detector in use: not an AI model, but one the app runs on covers. */
    private fun addCoverText(list: LinearLayout) {
        val title = layoutInflater.inflate(R.layout.item_section_title, list, false) as TextView
        title.setText(R.string.cover_text_section)
        list.addView(title)
        val card = layoutInflater.inflate(R.layout.card_models, list, false) as MaterialCardView
        card.id = R.id.cover_text_card
        val rowsBox = card.findViewById<LinearLayout>(R.id.card_rows)
        val row = layoutInflater.inflate(R.layout.item_model, rowsBox, false)
        row.findViewById<MaterialButton>(R.id.action).apply {
            isVisible = false
            setText(R.string.ai_download)
            setOnClickListener { DetectorModel.shared(this@ModelsActivity).fetch() }
        }
        row.isClickable = false
        rowsBox.addView(row)
        list.addView(card)
        coverTextRow = row
        showCoverText()
    }

    /** Shows where PP-OCR's model is ([detectorFiles]). */
    private fun showCoverText() {
        val row = coverTextRow ?: return
        val shown = ModelText.coverText(this, detectorFiles)
        row.findViewById<TextView>(R.id.title).text = shown.title
        row.findViewById<TextView>(R.id.subtitle).text = shown.details
        row.findViewById<TextView>(R.id.status).apply {
            text = shown.status
            isVisible = shown.status.isNotEmpty()
        }
        row.findViewById<LinearProgressIndicator>(R.id.progress).apply {
            isVisible = shown.progress != null
            shown.progress?.let { setProgressCompat(it, true) }
        }
        row.findViewById<MaterialButton>(R.id.action).isVisible = shown.download
    }

    override fun onResume() {
        super.onResume()
        showCoverText()
    }

    /** Every row's state, and the space models take, as [d] and the store say. */
    private suspend fun show(d: Downloads) {
        if (UserModels.all != builtFor) build()
        val models = Models.all()
        val installed = withContext(AppDispatchers.io) { models.filter { store.installed(it) }.map { it.id }.toSet() }
        val used = withContext(AppDispatchers.io) { store.bytesUsed() }
        val detector = ModelCatalog.TEXT_DETECTOR
        detectorFiles = ModelText.DetectorFiles(withContext(AppDispatchers.io) { store.installed(detector) }, d.states[detector.id])
        showCoverText()
        findViewById<TextView>(R.id.models_used).text = getString(R.string.ai_models_used, ModelText.size(this, used))
        val simple = prefs.keepSimple
        for (m in models) {
            val row = rows[m.id] ?: continue
            val status = row.findViewById<TextView>(R.id.status)
            val progress = row.findViewById<LinearProgressIndicator>(R.id.progress)
            val action = row.findViewById<MaterialButton>(R.id.action)
            if (m is UserModel) {
                status.isVisible = false
                progress.isVisible = false
                action.setText(R.string.delete)
                action.setOnClickListener { confirmDelete(m) }
                continue
            }
            m as FileSet
            // Kept simple: no slow models, unless installed or downloading.
            row.isVisible = !simple || !m.slow || m.id in installed || d.states[m.id] != null
            when (val s = d.states[m.id]) {
                is DownloadState.Running -> {
                    status.isVisible = false
                    progress.isVisible = true
                    progress.setProgressCompat(s.percent, true)
                    action.setText(R.string.cancel)
                    action.setOnClickListener { ModelDownloads.cancel(m.id) }
                }
                is DownloadState.Failed -> {
                    status.isVisible = true
                    status.text = ModelText.failure(this, s.failure)
                    progress.isVisible = false
                    action.setText(R.string.ai_download)
                    action.setOnClickListener { ModelDownloads.start(store, m) }
                }
                null -> {
                    progress.isVisible = false
                    if (m.id in installed) {
                        status.isVisible = true
                        status.setText(R.string.ai_downloaded)
                        action.setText(R.string.delete)
                        action.setOnClickListener { ModelDownloads.delete(store, m) }
                    } else {
                        status.isVisible = false
                        action.setText(R.string.ai_download)
                        action.setOnClickListener { ModelDownloads.start(store, m) }
                    }
                }
            }
        }
    }

    private fun confirmDelete(m: UserModel) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.ai_model_delete_imported, m.name))
            .setPositiveButton(R.string.delete) { _, _ -> ModelImports.delete(this, m) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Shows import state [s]: what it waits for, how it goes, or how it ended. */
    private fun showImport(s: ImportState) {
        if (s == shownImport) return
        shownImport = s
        importDialog?.dismiss()
        importDialog = null
        importDialog = when (s) {
            ImportState.Idle -> null
            is ImportState.Half -> AlertDialog.Builder(this)
                .setTitle(R.string.ai_import_title)
                .setMessage(if (s.isParam) R.string.ai_import_pick_bin else R.string.ai_import_pick_param)
                .setPositiveButton(R.string.ai_import_pick) { _, _ -> pickFiles() }
                .setNegativeButton(R.string.cancel) { _, _ -> ModelImports.cancel() }
                .setCancelable(false)
                .show()
            is ImportState.Working -> {
                val view = layoutInflater.inflate(R.layout.dialog_import_progress, null)
                view.findViewById<TextView>(R.id.import_progress_text).text =
                    getString(if (s.checking) R.string.ai_import_checking else R.string.ai_import_copying, s.name)
                AlertDialog.Builder(this)
                    .setTitle(R.string.ai_import_title)
                    .setView(view)
                    .setNegativeButton(R.string.cancel) { _, _ -> ModelImports.cancel() }
                    .setCancelable(false)
                    .show()
            }
            is ImportState.Fit -> when {
                s.warned -> warningDialog(s)
                s.previewing -> previewDialog(s)
                else -> fitDialog(s)
            }
            is ImportState.Refused -> AlertDialog.Builder(this)
                .setTitle(getString(R.string.ai_import_refused, s.name))
                .setMessage(ModelText.refusal(this, s))
                .setPositiveButton(R.string.ok) { _, _ -> ModelImports.dismiss(this) }
                .setCancelable(false)
                .show()
            is ImportState.Failed -> AlertDialog.Builder(this)
                .setTitle(R.string.ai_import_failed)
                .setMessage(ModelText.failure(this, s.why))
                .setPositiveButton(R.string.ok) { _, _ -> ModelImports.dismiss(this) }
                .setCancelable(false)
                .show()
            is ImportState.Imported -> {
                Toast.makeText(this, getString(R.string.ai_import_done, s.model.name), Toast.LENGTH_SHORT).show()
                ModelImports.dismiss(this)
                null
            }
        }
    }

    /** Asks whether to import a fit model with warnings anyway; Cancel, or Back, drops it. */
    private fun warningDialog(s: ImportState.Fit): AlertDialog = AlertDialog.Builder(this)
        .setTitle(getString(R.string.ai_import_warn_title, s.suggested))
        .setMessage(ModelText.warnings(this, s))
        .setPositiveButton(R.string.ai_import_anyway) { _, _ -> ModelImports.accept() }
        .setNegativeButton(R.string.cancel) { _, _ -> ModelImports.dismiss(this) }
        .setOnCancelListener { ModelImports.dismiss(this) }
        .create()
        .apply {
            setCanceledOnTouchOutside(false)
            show()
        }

    /**
     * Shows a fit model's before and after on each test picture at the same size
     * ([ImportPreviewZoom]), side by side where they fit, else one above the other ([ButtonRow]);
     * judging nothing: Continue goes on to naming it, Discard, or Back, drops it.
     */
    private fun previewDialog(s: ImportState.Fit): AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_import_preview, null)
        val list = view.findViewById<LinearLayout>(R.id.import_preview_list)
        val density = resources.displayMetrics.densityDpi
        for (p in s.previews) {
            val item = layoutInflater.inflate(R.layout.item_import_preview, list, false)
            val label = ModelText.previewPicture(this, p.picture)
            item.findViewById<TextView>(R.id.import_preview_label).text = label
            val side = ImportPreviewZoom.side(p.before, p.after)
            for ((id, bitmap, said) in listOf(
                Triple(R.id.import_preview_before, p.before, R.string.ai_import_preview_before),
                Triple(R.id.import_preview_after, p.after, R.string.ai_import_preview_after)
            )) {
                item.findViewById<ImageView>(id).apply {
                    setImageBitmap(ImportPreviewZoom.shown(bitmap, side).also { it.density = density })
                    contentDescription = getString(R.string.ai_import_preview_image, label, getString(said))
                }
            }
            list.addView(item)
        }
        return AlertDialog.Builder(this)
            .setTitle(getString(R.string.ai_import_preview_title, s.suggested))
            .setView(view)
            .setPositiveButton(R.string.ai_import_preview_next) { _, _ -> ModelImports.previewed() }
            .setNegativeButton(R.string.ai_import_discard) { _, _ -> ModelImports.dismiss(this) }
            .setOnCancelListener { ModelImports.dismiss(this) }
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
                show()
            }
    }

    /** Asks for a checked model's name and step, warning that heavy models may be too slow. */
    private fun fitDialog(s: ImportState.Fit): AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_import_model, null)
        val seconds = ModelText.pageSeconds(s)
        view.findViewById<TextView>(R.id.import_found).text =
            if (s.scale == 1) getString(R.string.ai_import_found_keeps, seconds) else getString(R.string.ai_import_found_enlarges, s.scale, seconds)
        val name = view.findViewById<TextInputEditText>(R.id.import_name)
        name.setText(s.suggested)
        val group = view.findViewById<RadioGroup>(R.id.import_steps)
        val steps = ModelImports.steps(s.scale)
        for ((i, step) in steps.withIndex()) {
            group.addView(MaterialRadioButton(this).apply {
                id = View.generateViewId()
                tag = step
                text = ModelText.step(this@ModelsActivity, step)
                minHeight = (48 * resources.displayMetrics.density).toInt()
                isChecked = i == 0
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ai_import_title)
            .setView(view)
            .setPositiveButton(R.string.ai_import_save, null)
            .setNegativeButton(R.string.ai_import_discard) { _, _ -> ModelImports.dismiss(this) }
            .setCancelable(false)
            .show()
        // Kept open while the name is empty.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = name.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                view.findViewById<TextInputLayout>(R.id.import_name_box).error = getString(R.string.ai_import_name)
                return@setOnClickListener
            }
            val step = group.findViewById<View>(group.checkedRadioButtonId)?.tag as? AiStep ?: steps.first()
            ModelImports.save(this, text, step)
        }
        return dialog
    }

    /** Shows what is known of [m] under its [row] while it is opened; a screen reader hears what a tap does. */
    private fun bindInfo(row: View, m: AiModel) {
        val shown = m.id in opened
        row.findViewById<View>(R.id.model_info).isVisible = shown
        val label = getString(if (shown) R.string.ai_model_less_info else R.string.ai_model_more_info, m.name)
        ViewCompat.replaceAccessibilityAction(row, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, label, null)
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        /** The saved state's ids of the rows showing what is known of their models. */
        const val OPENED = "opened"
    }
}
