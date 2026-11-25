package minty.emanata.ui

import android.app.Dialog
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import minty.emanata.data.Prefs

/**
 * Shows one tab's sort or filter popup and brings it back after a rotation. Both
 * write every change at once, so a rebuilt popup starts from what is saved.
 */
class TabDialogFragment : DialogFragment() {
    enum class Kind { SORT, FILTER }

    /** The screen that shows each change at once. */
    interface Host {
        fun onTabSettingsChanged()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val tab = args.getString(ARG_TAB) ?: Prefs.TAB_BROWSE
        val prefs = Prefs(requireContext())
        val onChange = { (activity as? Host)?.onTabSettingsChanged() ?: Unit }
        return when (Kind.valueOf(args.getString(ARG_KIND) ?: Kind.SORT.name)) {
            Kind.SORT -> SortDialog.create(requireContext(), prefs, tab, onChange)
            Kind.FILTER -> FilterDialog.create(requireContext(), prefs, tab, onChange)
        }
    }

    companion object {
        private const val ARG_KIND = "kind"
        private const val ARG_TAB = "tab"
        private const val TAG = "tab_dialog"

        fun show(activity: FragmentActivity, kind: Kind, tab: String) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved) return
            (fm.findFragmentByTag(TAG) as? DialogFragment)?.dismiss()
            TabDialogFragment().apply { arguments = bundleOf(ARG_KIND to kind.name, ARG_TAB to tab) }.show(fm, TAG)
        }
    }
}
