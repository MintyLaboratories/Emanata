package com.infinity.comicreader.ui

import android.app.Dialog
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.LocationSpace
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.StorageRoot
import com.infinity.comicreader.data.StorageRoots
import com.infinity.comicreader.net.RemoteException
import com.infinity.comicreader.net.Remotes
import com.infinity.comicreader.net.ShareKind
import com.infinity.comicreader.net.ShareStore
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The location picker the app icon crumb opens: every storage root with its icon (a
 * cloud for a WebDAV share), name and free space, the current one highlighted (or a
 * drive just plugged in), then "Add network share…". A share says "Not reachable" (or
 * why it failed) instead of its free space; tapping it tries it again, a long press
 * edits or removes it, or sets its start folder ([Prefs.locationStart]), as a long
 * press on the device does.
 * Tapping a root hands it to the [Host] activity. [update] redraws an open sheet as
 * drives and shares come and go; the sheet comes back after a rotation. Free space
 * is read off the main thread and kept ([LocationSpace]): a share's shows as kept and
 * is asked over the network only when that is over an hour old.
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
            val share = Remotes.idOf(root.path)
            val webDav = share != null && Remotes.share(share)?.kind == ShareKind.WEBDAV
            row.findViewById<ImageView>(R.id.location_icon).setImageResource(if (webDav) R.drawable.ic_cloud else iconOf(root.kind))
            row.findViewById<TextView>(R.id.location_name).text = root.name
            val detail = row.findViewById<TextView>(R.id.location_detail)
            if (share != null) {
                detail.setText(R.string.share_connecting)
                detail.visibility = View.VISIBLE
                row.setOnLongClickListener {
                    shareMenu(share, root)
                    true
                }
            } else {
                detail.visibility = View.GONE
                if (root.kind == StorageRoot.Kind.DEVICE) {
                    row.setOnLongClickListener {
                        deviceMenu(root)
                        true
                    }
                }
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
        val spaces = LocationSpace(context)
        for ((i, root) in roots.withIndex()) {
            val kept = spaces.get(root.path)
            if (kept != null) showSpace(details[i], kept.free, kept.total)
            if (Remotes.idOf(root.path) != null && !spaces.stale(kept)) continue
            spaceJobs += lifecycleScope.launch {
                val space = withContext(AppDispatchers.io) { runCatching { StorageRoots.space(root) } }
                space.fold(
                    onSuccess = { found ->
                        val (free, total) = found ?: run {
                            details[i].visibility = View.GONE
                            return@launch
                        }
                        spaces.put(root.path, free, total)
                        showSpace(details[i], free, total)
                    },
                    onFailure = {
                        details[i].text = context.getString(failureText(it))
                        details[i].visibility = View.VISIBLE
                    }
                )
            }
        }
    }

    private fun showSpace(detail: TextView, free: Long, total: Long) {
        val context = detail.context
        detail.text = context.getString(
            R.string.location_free,
            Formatter.formatShortFileSize(context, free),
            Formatter.formatShortFileSize(context, total)
        )
        detail.visibility = View.VISIBLE
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

    /** The device's start folder. */
    private fun deviceMenu(root: StorageRoot) {
        AlertDialog.Builder(requireContext())
            .setTitle(root.name)
            .setItems(arrayOf(startLabel(root))) { _, _ -> pickStart(root) }
            .show()
    }

    /** "Start folder…", naming where [root] opens when that is not its top. */
    private fun startLabel(root: StorageRoot): String {
        val below = root.segments(Prefs(requireContext()).locationStart(root.path))
        return if (below.isEmpty()) getString(R.string.location_start_folder) else getString(R.string.location_start_folder_set, below.joinToString("/"))
    }

    /** Browses [root] for the folder it opens at from now on; choosing its top clears it. */
    private fun pickStart(root: StorageRoot) {
        val owner = activity ?: return
        val prefs = Prefs(owner)
        FolderPicker(owner, owner.lifecycleScope) { picked ->
            prefs.setLocationStart(root.path, picked)
            Toast.makeText(owner, owner.getString(R.string.location_start_set, FolderPicker.describe(owner, prefs.locationStart(root.path))), Toast.LENGTH_SHORT).show()
        }.browseFrom(prefs.locationStart(root.path))
    }

    /** Edit or remove the share [id] at [root], or set its start folder. */
    private fun shareMenu(id: String, root: StorageRoot) {
        val context = requireContext()
        val name = root.name
        AlertDialog.Builder(context)
            .setTitle(name)
            .setItems(arrayOf(getString(R.string.share_edit), startLabel(root), getString(R.string.share_remove))) { _, which ->
                val owner = activity ?: return@setItems
                if (which == 0) {
                    ShareDialog.show(owner, id)
                } else if (which == 1) {
                    pickStart(root)
                } else {
                    confirmRemove(owner, id, root)
                }
            }
            .show()
    }

    /**
     * Asks before removing share [id] at [root], offering to forget its favorites, history
     * and bookmarks too (not ticked).
     */
    private fun confirmRemove(owner: FragmentActivity, id: String, root: StorageRoot) {
        val app = owner.applicationContext
        val extra = LayoutInflater.from(owner).inflate(R.layout.dialog_check, null)
        val forget = extra.findViewById<CheckBox>(R.id.dialog_check).apply { setText(R.string.share_remove_library) }
        AlertDialog.Builder(owner)
            .setTitle(R.string.share_remove_title)
            .setMessage(getString(R.string.share_remove_msg, root.name))
            .setView(extra)
            .setPositiveButton(R.string.share_remove) { _, _ ->
                val dropLibrary = forget.isChecked
                owner.lifecycleScope.launch {
                    // Closing the share's connection may wait on the network.
                    withContext(NonCancellable + AppDispatchers.io) {
                        ShareStore(app).remove(id)
                        Prefs(app).setLocationStart(root.path, null)
                        LocationSpace(app).remove(root.path)
                        if (dropLibrary) Library(app).removePathsUnder(root.path)
                    }
                    (owner as? Host)?.onSharesChanged()
                }
            }
            .setNegativeButton(R.string.cancel, null)
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

        /** What stopped a share, by its name (one argument), when asking it failed with [error]. */
        fun failureTextNamed(error: Throwable): Int = when ((error as? RemoteException)?.reason) {
            RemoteException.Reason.DENIED -> R.string.share_denied_named
            RemoteException.Reason.NO_SHARE -> R.string.share_missing_named
            else -> R.string.share_unreachable_named
        }
    }
}
