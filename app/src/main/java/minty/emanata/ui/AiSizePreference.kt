package minty.emanata.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import minty.emanata.R
import minty.emanata.ai.AiThresholds
import minty.emanata.data.Prefs

/**
 * The AI size setting: an [AiSizeSlider] over [Prefs.aiThresholds] and the sentence under it,
 * which follows the handles live. A value is stored when a drag ends, or on each key or
 * accessibility step.
 */
class AiSizePreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_ai_sizes
        isPersistent = false
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val prefs = Prefs(context)
        val slider = holder.findViewById(R.id.ai_sizes_slider) as AiSizeSlider
        val sentence = holder.findViewById(R.id.ai_sizes_sentence) as TextView
        fun show(t: AiThresholds) {
            sentence.text = AiSizeText.sentence(context, t, slider.shown(slider.upscaleColor), slider.shown(slider.cleanupColor))
        }
        slider.thresholds = prefs.aiThresholds
        show(slider.thresholds)
        slider.onChange = { t, done ->
            show(t)
            if (done) prefs.aiThresholds = t
        }
    }
}
