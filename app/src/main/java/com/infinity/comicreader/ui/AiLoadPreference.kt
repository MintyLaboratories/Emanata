package com.infinity.comicreader.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.infinity.comicreader.R

/** A default AI chain's load estimate ([AiLoadBar]) at [pageMs], under the chain's pickers in Settings. */
class AiLoadPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_ai_load
        isPersistent = false
        isSelectable = false
    }

    /** The chain's page time, as its bar shows it. */
    var pageMs: Long = 0
        set(v) {
            if (v == field) return
            field = v
            notifyChanged()
        }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.ai_load_bar) as AiLoadBar).apply {
            pageMs = this@AiLoadPreference.pageMs
            isEnabled = this@AiLoadPreference.isEnabled
        }
    }
}
