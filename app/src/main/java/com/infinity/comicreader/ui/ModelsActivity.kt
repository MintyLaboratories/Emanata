package com.infinity.comicreader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiChain
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.CatalogModel
import com.infinity.comicreader.ai.DownloadFailure
import com.infinity.comicreader.ai.DownloadState
import com.infinity.comicreader.ai.Downloads
import com.infinity.comicreader.ai.ModelBlurb
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelDownloads
import com.infinity.comicreader.ai.ModelStore
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Words for catalog models, shared by the model manager and Settings. */
object ModelText {
    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun step(context: Context, step: AiStep): String = context.getString(
        when (step) {
            AiStep.ARTIFACTS -> R.string.ai_step_artifacts
            AiStep.DESCREEN -> R.string.ai_step_descreen
            AiStep.UPSCALE -> R.string.ai_step_upscale
        }
    )

    /** Scale, download size and license. */
    fun details(context: Context, m: CatalogModel): String =
        context.getString(R.string.ai_model_details, m.scale, size(context, m.bytes), m.source.license)

    /**
     * What [m] does ([CatalogModel.blurb]), marked when it is the default, then a line saying
     * how slow it is when it is ([CatalogModel.slow]); empty for neither.
     */
    fun blurb(context: Context, m: CatalogModel): String {
        val lines = ArrayList<String>(2)
        m.blurb?.let { b ->
            val text = context.getString(blurbText(b))
            lines += if (m.id == ModelCatalog.DEFAULT_UPSCALE) context.getString(R.string.ai_model_default, text) else text
        }
        if (m.slow) lines += context.getString(R.string.ai_model_slow, m.pageSeconds)
        return lines.joinToString("\n")
    }

    private fun blurbText(b: ModelBlurb): Int = when (b) {
        ModelBlurb.WAIFU2X -> R.string.ai_blurb_waifu2x
        ModelBlurb.WAIFU2X_DENOISE -> R.string.ai_blurb_waifu2x_denoise
        ModelBlurb.OPENCOMIC_UPSCALE -> R.string.ai_blurb_opencomic_upscale
        ModelBlurb.REAL_ESRGAN_ANIME -> R.string.ai_blurb_real_esrgan_anime
        ModelBlurb.DESCREEN_V2 -> R.string.ai_blurb_descreen_v2
        ModelBlurb.DESCREEN_V3 -> R.string.ai_blurb_descreen_v3
        ModelBlurb.ARTIFACT_REMOVAL -> R.string.ai_blurb_artifact_removal
    }

    /** A picker's entry for [m]: its name and size, and below, smaller and quieter, its [blurb]. */
    fun entry(context: Context, m: CatalogModel): CharSequence {
        val head = context.getString(R.string.ai_model_entry, m.name, size(context, m.bytes))
        val more = blurb(context, m)
        if (more.isEmpty()) return head
        val out = SpannableStringBuilder(head).append('\n')
        val start = out.length
        out.append(more)
        out.setSpan(RelativeSizeSpan(BLURB_SIZE), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val quiet = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        if (quiet != 0) out.setSpan(ForegroundColorSpan(quiet), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    /** A picker's choices for [step]: none, then the models it offers ([ModelCatalog.offered]), with their catalog ids (none's [Prefs.AI_NONE]). */
    fun choices(context: Context, step: AiStep, keepSimple: Boolean, current: String?): Pair<List<CharSequence>, List<String>> {
        val models = ModelCatalog.offered(step, keepSimple, current)
        return (listOf<CharSequence>(context.getString(R.string.ai_none)) + models.map { entry(context, it) }) to
            (listOf(Prefs.AI_NONE) + models.map { it.id })
    }

    /** The size of a [blurb] beside a model's name. */
    private const val BLURB_SIZE = 0.85f

    fun failure(context: Context, f: DownloadFailure): String = context.getString(
        when (f) {
            DownloadFailure.NETWORK -> R.string.ai_download_failed_network
            DownloadFailure.SERVER -> R.string.ai_download_failed_server
            DownloadFailure.MISMATCH -> R.string.ai_download_failed_mismatch
            DownloadFailure.SPACE -> R.string.ai_download_failed_space
        }
    )

    /** A step's choice [m] (null: none), [installed] or in download [state]. */
    fun choice(context: Context, m: CatalogModel?, installed: Boolean, state: DownloadState?): String = when {
        m == null -> context.getString(R.string.ai_none)
        installed -> m.name
        state is DownloadState.Running -> context.getString(R.string.ai_model_downloading, m.name, state.percent)
        state is DownloadState.Failed -> context.getString(R.string.ai_model_failed, m.name)
        else -> context.getString(R.string.ai_model_missing, m.name)
    }

    /**
     * AI processing's description, then the models [chain] runs, in order, and a warning when
     * together they would run past a page's time ([AiChain.slow]).
     */
    fun chain(context: Context, chain: List<CatalogModel>): String {
        val names = if (chain.isEmpty()) context.getString(R.string.ai_none) else chain.joinToString(" → ") { it.name }
        val lines = listOf(context.getString(R.string.pref_ai_desc), context.getString(R.string.ai_chain, names))
        return (if (AiChain.slow(chain)) lines + context.getString(R.string.ai_chain_slow) else lines).joinToString("\n")
    }

    /** Who made [m], its license, where it is from and where it downloads from. */
    fun attribution(context: Context, m: CatalogModel): String = context.getString(
        R.string.ai_model_info, details(context, m), m.source.author, m.source.license, m.source.url, ModelCatalog.REPOSITORY
    )
}

/**
 * The AI model manager: every catalog model by step, with what it does, its size and
 * license, downloaded or not; downloads (with progress, resumed when cut short), cancels and
 * deletes. With Keep it simple on, slow models show only once downloaded or downloading. A
 * model's row opens its attribution.
 */
class ModelsActivity : AppCompatActivity() {
    private lateinit var store: ModelStore
    private val rows = HashMap<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)
        val toolbar = findViewById<MaterialToolbar>(R.id.models_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        store = ModelStore.of(this)

        val list = findViewById<LinearLayout>(R.id.models_list)
        for (step in AiStep.entries) {
            val models = ModelCatalog.forStep(step)
            if (models.isEmpty()) continue
            val header = layoutInflater.inflate(R.layout.item_section_title, list, false) as TextView
            header.text = ModelText.step(this, step)
            list.addView(header)
            val card = layoutInflater.inflate(R.layout.card_models, list, false) as MaterialCardView
            val rowsBox = card.findViewById<LinearLayout>(R.id.card_rows)
            for (m in models) {
                val row = layoutInflater.inflate(R.layout.item_model, rowsBox, false)
                row.findViewById<TextView>(R.id.title).text = m.name
                row.findViewById<TextView>(R.id.blurb).apply {
                    text = ModelText.blurb(this@ModelsActivity, m)
                    isVisible = text.isNotEmpty()
                }
                row.findViewById<TextView>(R.id.subtitle).text = ModelText.details(this, m)
                // Shown once the store says whether it is downloaded.
                row.isVisible = !m.slow
                row.setOnClickListener { showInfo(m) }
                rowsBox.addView(row)
                rows[m.id] = row
            }
            list.addView(card)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloads.state.collect { show(it) }
            }
        }
    }

    /** Every row's state, and the space models take, as [d] and the store say. */
    private suspend fun show(d: Downloads) {
        val installed = withContext(AppDispatchers.io) { ModelCatalog.all.filter { store.installed(it) }.map { it.id }.toSet() }
        val used = withContext(AppDispatchers.io) { store.bytesUsed() }
        findViewById<TextView>(R.id.models_used).text = getString(R.string.ai_models_used, ModelText.size(this, used))
        val simple = Prefs(this).keepSimple
        for (m in ModelCatalog.all) {
            val row = rows[m.id] ?: continue
            row.isVisible = !simple || !m.slow || m.id in installed || d.states[m.id] != null
            val status = row.findViewById<TextView>(R.id.status)
            val progress = row.findViewById<LinearProgressIndicator>(R.id.progress)
            val action = row.findViewById<MaterialButton>(R.id.action)
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

    private fun showInfo(m: CatalogModel) {
        AlertDialog.Builder(this)
            .setTitle(m.name)
            .setMessage(ModelText.attribution(this, m))
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.about_project_page) { _, _ -> open(m.source.url) }
            .show()
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
