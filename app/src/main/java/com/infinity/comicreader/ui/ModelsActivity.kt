package com.infinity.comicreader.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
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
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.CatalogModel
import com.infinity.comicreader.ai.DownloadFailure
import com.infinity.comicreader.ai.DownloadState
import com.infinity.comicreader.ai.Downloads
import com.infinity.comicreader.ai.ModelCatalog
import com.infinity.comicreader.ai.ModelDownloads
import com.infinity.comicreader.ai.ModelStore
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

    /** Who made [m], its license, where it is from and where it downloads from. */
    fun attribution(context: Context, m: CatalogModel): String = context.getString(
        R.string.ai_model_info, details(context, m), m.source.author, m.source.license, m.source.url, ModelCatalog.REPOSITORY
    )
}

/**
 * The AI model manager: every catalog model by step, with its size and license, downloaded
 * or not; downloads (with progress, resumed when cut short), cancels and deletes. A model's
 * row opens its attribution.
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
                row.findViewById<TextView>(R.id.subtitle).text = ModelText.details(this, m)
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
        for (m in ModelCatalog.all) {
            val row = rows[m.id] ?: continue
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
