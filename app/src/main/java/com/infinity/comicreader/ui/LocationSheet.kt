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
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.infinity.comicreader.R
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareStore
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The location picker the app icon crumb opens: every storage root with its icon, name
 * and free space, the current one highlighted (or a drive just plugged in), then
 * "Add network share…". A share says "Not reachable" (or why it failed) instead of
 * its free space; tapping it tries it again, a long press edits or removes it.
 * Tapping a root hands it to the [Host] activity. [update] redraws an open sheet as
 * drives and shares come and go; the sheet comes back after a rotation. Free space
 * is read off the main thread, a share's over the network.
 */
class LocationSheet : BottomSheetDialogFragment() {
    /** The activity showing the sheet. */
    interface Host {
        /** The storage roots to list now. */
        val locationRoots: List<StorageRoot>

        fun onLocationPicked(root: StorageRoot)

        /** A share was added, edited or removed. */
        fun onSharesChanged()
    }

    private var list: LinearLayout? = null
    private val spaceJobs = ArrayList<Job>()

    /** The rows shown, for tests. */
    internal val shownRoots = ArrayList<StorageRoot>()

    val isShowing: Boolean get() = dialog?.isShowing == true

    private val host: Host? get() = activity as? Host

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.sheet_locations, null)
        list = view.findViewById(R.id.locations_list)
        return BottomSheetDialog(requireContext(), theme).apply {
            setContentView(view)
            // Opens whole: in landscape the collapsed height showed only the first row.
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            SheetExit.passTouchesWhileLeaving(this)
        }
    }

    override fun onStart() {
        super.onStart()
        host?.let { fill(it.locationRoots) }
    }

    override fun onDestroyView() {
        cancelSpace()
        list = null
        super.onDestroyView()
    }

    /** Shows [roots] instead, when open. */
    fun update(roots: List<StorageRoot>) {
        if (list != null) fill(roots)
    }

    private fun cancelSpace() {
        spaceJobs.forEach { it.cancel() }
        spaceJobs.clear()
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
            val share = Remotes.idOf(root.path)
            if (share != null) {
                detail.setText(R.string.share_connecting)
                detail.visibility = View.VISIBLE
                row.setOnLongClickListener {
                    shareMenu(share, root.name)
                    true
                }
            } else {
                detail.visibility = View.GONE
            }
            details += detail
            row.isActivated = root.path == current
            row.setOnClickListener {
                val picked = host
                dismissAllowingStateLoss()
                picked?.onLocationPicked(root)
            }
            container.addView(row)
        }
        container.addView(addRow(inflater, container))
        cancelSpace()
        for ((i, root) in roots.withIndex()) {
            spaceJobs += lifecycleScope.launch {
                val space = withContext(AppDispatchers.io) { runCatching { StorageRoots.space(root) } }
                details[i].text = space.fold(
                    onSuccess = { found ->
                        val (free, total) = found ?: run {
                            details[i].visibility = View.GONE
                            return@launch
                        }
                        context.getString(
                            R.string.location_free,
                            Formatter.formatShortFileSize(context, free),
                            Formatter.formatShortFileSize(context, total)
                        )
                    },
                    onFailure = { context.getString(failureText(it)) }
                )
                details[i].visibility = View.VISIBLE
            }
        }
    }

    /** The "Add network share…" row. */
    private fun addRow(inflater: LayoutInflater, container: LinearLayout): View {
        val row = inflater.inflate(R.layout.item_location, container, false)
        row.findViewById<ImageView>(R.id.location_icon).setImageResource(R.drawable.ic_add)
        row.findViewById<TextView>(R.id.location_name).setText(R.string.share_add)
        row.findViewById<View>(R.id.location_detail).visibility = View.GONE
        row.id = R.id.location_add
        row.setOnClickListener { activity?.let { ShareDialog.show(it, null) } }
        return row
    }

    /** Edit or remove the share [id], named [name]. */
    private fun shareMenu(id: String, name: String) {
        val context = requireContext()
        AlertDialog.Builder(context)
            .setTitle(name)
            .setItems(arrayOf(getString(R.string.share_edit), getString(R.string.share_remove))) { _, which ->
                val owner = activity ?: return@setItems
                if (which == 0) {
                    ShareDialog.show(owner, id)
                } else {
                    val app = context.applicationContext
                    confirm(context, R.string.share_remove_title, getString(R.string.share_remove_msg, name), R.string.share_remove) {
                        owner.lifecycleScope.launch {
                            // Closing the share's connection may wait on the network.
                            withContext(NonCancellable + AppDispatchers.io) { ShareStore(app).remove(id) }
                            (owner as? Host)?.onSharesChanged()
                        }
                    }
                }
            }
            .show()
    }

    companion object {
        private const val TAG = "locations"
        private const val ARG_CURRENT = "current"

        /**
         * Opens the sheet over [activity], which must be a [Host], with [current] highlighted;
         * false when the activity's state is already saved and nothing opened.
         */
        fun show(activity: FragmentActivity, current: StorageRoot?): Boolean {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved) return false
            find(activity)?.dismiss()
            LocationSheet().apply { arguments = bundleOf(ARG_CURRENT to current?.path) }.show(fm, TAG)
            return true
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

        /** What a share's row says when asking it failed with [error]. */
        fun failureText(error: Throwable): Int = when ((error as? RemoteException)?.reason) {
            RemoteException.Reason.DENIED -> R.string.share_denied
            RemoteException.Reason.NO_SHARE -> R.string.share_missing
            else -> R.string.share_unreachable
        }
    }
}
