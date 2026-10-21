package com.infinity.comicreader.ui

import android.app.Dialog
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.infinity.comicreader.R
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The location picker the app icon crumb opens: every storage root with its icon, name
 * and free space, the current one highlighted. Tapping one hands it to the [Host]
 * activity. [update] redraws an open sheet as drives come and go; the sheet comes
 * back after a rotation. Free space is read off the main thread.
 */
class LocationSheet : BottomSheetDialogFragment() {
    /** The activity showing the sheet. */
    interface Host {
        /** The storage roots to list now. */
        val locationRoots: List<StorageRoot>

        fun onLocationPicked(root: StorageRoot)
    }

    private var list: LinearLayout? = null
    private var spaceJob: Job? = null

    /** The rows shown, for tests. */
    internal val shownRoots = ArrayList<StorageRoot>()

    val isShowing: Boolean get() = dialog?.isShowing == true

    private val host: Host? get() = activity as? Host

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.sheet_locations, null)
        list = view.findViewById(R.id.locations_list)
        return BottomSheetDialog(requireContext(), theme).apply { setContentView(view) }
    }

    override fun onStart() {
        super.onStart()
        host?.let { fill(it.locationRoots) }
    }

    override fun onDestroyView() {
        spaceJob?.cancel()
        list = null
        super.onDestroyView()
    }

    /** Shows [roots] instead, when open. */
    fun update(roots: List<StorageRoot>) {
        if (list != null) fill(roots)
    }

    private fun fill(roots: List<StorageRoot>) {
        val container = list ?: return
        val context = requireContext()
        val current = arguments?.getString(ARG_CURRENT)
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
            row.isActivated = root.path == current
            row.setOnClickListener {
                val picked = host
                dismissAllowingStateLoss()
                picked?.onLocationPicked(root)
            }
            container.addView(row)
        }
        spaceJob?.cancel()
        spaceJob = lifecycleScope.launch {
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
        private const val TAG = "locations"
        private const val ARG_CURRENT = "current"

        /** Opens the sheet over [activity], which must be a [Host], with [current] highlighted. */
        fun show(activity: FragmentActivity, current: StorageRoot?) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved) return
            find(activity)?.dismiss()
            LocationSheet().apply { arguments = bundleOf(ARG_CURRENT to current?.path) }.show(fm, TAG)
        }

        /** The sheet open over [activity]; null when none is. */
        fun find(activity: FragmentActivity): LocationSheet? = activity.supportFragmentManager.findFragmentByTag(TAG) as? LocationSheet

        @DrawableRes
        fun iconOf(kind: StorageRoot.Kind): Int = when (kind) {
            StorageRoot.Kind.DEVICE -> R.drawable.ic_tablet
            StorageRoot.Kind.REMOVABLE -> R.drawable.ic_sd_card
            StorageRoot.Kind.USB -> R.drawable.ic_usb
            StorageRoot.Kind.SHARE -> R.drawable.ic_dns
        }
    }
}
