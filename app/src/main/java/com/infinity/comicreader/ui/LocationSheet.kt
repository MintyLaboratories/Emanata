package com.infinity.comicreader.ui

import android.content.Context
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.infinity.comicreader.R
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The location picker the root crumb opens: every storage root with its icon, name
 * and free space, the current one highlighted. Tapping one hands it to [onPick].
 * [update] redraws an open sheet as drives come and go. Free space is read in
 * [scope], off the main thread.
 */
class LocationSheet(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPick: (StorageRoot) -> Unit
) {
    private var dialog: BottomSheetDialog? = null
    private var list: LinearLayout? = null
    private var current: StorageRoot? = null
    private var spaceJob: Job? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    /** The rows shown, for tests. */
    internal val shownRoots = ArrayList<StorageRoot>()

    fun show(roots: List<StorageRoot>, current: StorageRoot?) {
        dismiss()
        this.current = current
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_locations, null)
        list = view.findViewById(R.id.locations_list)
        dialog = BottomSheetDialog(context).apply {
            setContentView(view)
            setOnDismissListener {
                spaceJob?.cancel()
                if (dialog === this) {
                    dialog = null
                    list = null
                }
            }
            show()
        }
        fill(roots)
    }

    /** Shows [roots] instead, when open. */
    fun update(roots: List<StorageRoot>) {
        if (isShowing) fill(roots)
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        list = null
    }

    private fun fill(roots: List<StorageRoot>) {
        val container = list ?: return
        container.removeAllViews()
        shownRoots.clear()
        shownRoots.addAll(roots)
        val details = ArrayList<TextView>()
        val inflater = LayoutInflater.from(context)
        for (root in roots) {
            val row = inflater.inflate(R.layout.item_location, container, false)
            row.findViewById<ImageView>(R.id.location_icon).setImageResource(iconOf(root.kind))
            row.findViewById<TextView>(R.id.location_name).text = root.name
            val detail = row.findViewById<TextView>(R.id.location_detail)
            detail.visibility = View.GONE
            details += detail
            row.isActivated = root.path == current?.path
            row.setOnClickListener {
                dismiss()
                onPick(root)
            }
            container.addView(row)
        }
        spaceJob?.cancel()
        spaceJob = scope.launch {
            val spaces = withContext(AppDispatchers.io) { roots.map { StorageRoots.space(it) } }
            for ((i, space) in spaces.withIndex()) {
                val (free, total) = space ?: continue
                details[i].text = context.getString(
                    R.string.location_free,
                    Formatter.formatShortFileSize(context, free),
                    Formatter.formatShortFileSize(context, total)
                )
                details[i].visibility = View.VISIBLE
            }
        }
    }

    companion object {
        @DrawableRes
        fun iconOf(kind: StorageRoot.Kind): Int = when (kind) {
            StorageRoot.Kind.DEVICE -> R.drawable.ic_tablet
            StorageRoot.Kind.REMOVABLE -> R.drawable.ic_sd_card
            StorageRoot.Kind.USB -> R.drawable.ic_usb
            StorageRoot.Kind.SHARE -> R.drawable.ic_dns
        }
    }
}
