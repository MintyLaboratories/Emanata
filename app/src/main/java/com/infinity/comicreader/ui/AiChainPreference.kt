package com.infinity.comicreader.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors
import com.infinity.comicreader.R

/** A default AI chain's header in Settings: its name and what it is for; its load estimate follows its pickers ([AiLoadPreference]). */
class AiChainPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_ai_chain
        isPersistent = false
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // A preference that can't be selected gets the plain title color: a header keeps its own.
        (holder.findViewById(android.R.id.title) as? TextView)?.let { title ->
            title.setTextColor(MaterialColors.getColor(title, androidx.appcompat.R.attr.colorPrimary))
            title.alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
    }

    private companion object {
        /** A disabled header's title, as Material dims disabled text. */
        const val DISABLED_ALPHA = 0.38f
    }
}
