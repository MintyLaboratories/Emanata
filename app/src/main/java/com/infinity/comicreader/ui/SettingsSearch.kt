package com.infinity.comicreader.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spanned
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import com.infinity.comicreader.R
import com.infinity.comicreader.ai.AiStep
import com.infinity.comicreader.ai.ChainKind
import com.infinity.comicreader.data.Prefs
import java.text.Normalizer
import java.util.Locale

/**
 * A setting as search finds it: [title] as Settings shows it (an advanced one tagged), [plainTitle]
 * and [summary] as it is matched, and [place], the section it is in (an AI step also its chain).
 */
data class SettingEntry(
    val key: String,
    val title: CharSequence,
    val plainTitle: String,
    val summary: String,
    val place: String,
    val advanced: Boolean,
)

/** Finds settings by their titles and summaries, ignoring case and accents. */
object SettingsSearch {
    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    /** Places joined: a section, then an AI chain. */
    const val PLACE_SEPARATOR = " › "

    /** [text] lowercased, its accents and compatibility forms dropped: "Écran" and "ecran" match. */
    fun fold(text: CharSequence): String =
        Normalizer.normalize(text.toString().lowercase(Locale.ROOT), Normalizer.Form.NFKD).replace(MARKS, "")

    /**
     * The [entries] holding every word of [query] in their title or summary, in their order, those
     * with every word in the title first; none for a blank query.
     */
    fun find(entries: List<SettingEntry>, query: String): List<SettingEntry> {
        val words = fold(query).split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val found = entries.mapNotNull { entry ->
            val title = fold(entry.plainTitle)
            val all = title + "\n" + fold(entry.summary)
            if (words.all { it in all }) entry to words.all { it in title } else null
        }
        return found.sortedBy { (_, inTitle) -> if (inTitle) 0 else 1 }.map { it.first }
    }

    /** The keys of the AI steps' pickers, each to its chain. */
    private val PICKERS: Map<String, ChainKind> =
        ChainKind.entries.flatMap { kind -> AiStep.entries.map { Prefs.aiKey(it, kind) to kind } }.toMap()

    /**
     * Every titled setting in [screen], in order, whether shown or not: one outside a section has
     * no place; an AI step's picker is placed under the chain header ([AiChainPreference]) before it.
     */
    fun index(screen: PreferenceGroup): List<SettingEntry> {
        val out = ArrayList<SettingEntry>()
        for (i in 0 until screen.preferenceCount) {
            val pref = screen.getPreference(i)
            if (pref !is PreferenceGroup) {
                entry(pref, "")?.let(out::add)
                continue
            }
            val section = pref.title?.toString().orEmpty()
            var chain: AiChainPreference? = null
            for (j in 0 until pref.preferenceCount) {
                val child = pref.getPreference(j)
                if (child is AiChainPreference) chain = child
                val chained = chain?.takeIf { PICKERS.containsKey(child.key) }?.title
                entry(child, if (chained != null) section + PLACE_SEPARATOR + chained else section)?.let(out::add)
            }
        }
        return out
    }

    private fun entry(pref: Preference, place: String): SettingEntry? {
        val key = pref.key ?: return null
        val title = pref.title?.takeIf { it.isNotBlank() } ?: return null
        val summary = runCatching { pref.summary?.toString() }.getOrNull().orEmpty()
        return SettingEntry(key, title, plainTitle(title), summary, place, AdvancedSettings.isAdvanced(pref))
    }

    /** [title] without the tag [AdvancedSettings.styled] adds. */
    fun plainTitle(title: CharSequence): String {
        val tag = (title as? Spanned)?.let { s -> s.getSpans(0, s.length, TagSpan::class.java).minOfOrNull { s.getSpanStart(it) } }
        return (if (tag != null) title.subSequence(0, tag) else title).toString().trim()
    }
}

/** Collapsing or expanding every [CollapsibleCategory] of a settings screen at once. */
object SettingsFolds {
    fun sections(screen: PreferenceGroup): List<CollapsibleCategory> =
        (0 until screen.preferenceCount).mapNotNull { screen.getPreference(it) as? CollapsibleCategory }

    /** True while any section is expanded: the button then collapses all. */
    fun anyExpanded(screen: PreferenceGroup): Boolean = sections(screen).any { !it.collapsed }

    /** Collapses every section when [collapse], else expands every one; returns the keys left collapsed. */
    fun setAll(screen: PreferenceGroup, collapse: Boolean): Set<String> {
        val sections = sections(screen)
        for (s in sections) s.collapsed = collapse
        return if (collapse) sections.mapNotNull { it.key }.toSet() else emptySet()
    }
}

/**
 * The settings search field; [collapsing] is true while the toolbar closes it, whose emptying
 * of the text is not typing (it runs too when the toolbar's menu is made again).
 */
class SettingsSearchView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : SearchView(context, attrs) {
    var collapsing = false
        private set

    override fun onActionViewCollapsed() {
        collapsing = true
        try {
            super.onActionViewCollapsed()
        } finally {
            collapsing = false
        }
    }
}

/** Settings search results ([SettingEntry]): each a title and its place; a tap hands its key to [onPick]. */
class SettingResults(private val onPick: (String) -> Unit) : RecyclerView.Adapter<SettingResults.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.setting_result_title)
        val place: TextView = view.findViewById(R.id.setting_result_place)
    }

    var entries: List<SettingEntry> = emptyList()
        @SuppressLint("NotifyDataSetChanged") // Each search is a new list.
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    override fun getItemCount() = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_setting_result, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        holder.title.text = entry.title
        holder.place.text = entry.place
        holder.place.visibility = if (entry.place.isEmpty()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onPick(entry.key) }
    }
}

/**
 * Tints the row of one preference, found by key in a preference list, for [HOLD_MS], then fades
 * it out over [FADE_MS]; the tint follows the row as it scrolls or is bound again.
 */
class SettingFlash(color: Int) : RecyclerView.ItemDecoration() {
    private val paint = Paint().apply { this.color = color }
    private val baseAlpha = paint.alpha

    /** The key of the preference tinted now, if any. */
    var key: String? = null
        private set

    private var strength = 0f
    private var fade: ValueAnimator? = null
    private var list: RecyclerView? = null
    private val startFade = Runnable {
        fade = ValueAnimator.ofFloat(1f, 0f).setDuration(FADE_MS).apply {
            addUpdateListener {
                strength = it.animatedValue as Float
                list?.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fade === animation) stop()
                }
            })
            start()
        }
    }

    /** Tints [key]'s row in [list], replacing any tint still showing. */
    fun flash(list: RecyclerView, key: String) {
        stop()
        this.list = list
        this.key = key
        strength = 1f
        list.postDelayed(startFade, HOLD_MS)
        list.invalidate()
    }

    /** Ends the tint at once. */
    fun stop() {
        list?.removeCallbacks(startFade)
        fade?.let { fade = null; it.cancel() }
        list?.invalidate()
        key = null
        strength = 0f
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val k = key ?: return
        val position = (parent.adapter as? PreferenceGroup.PreferencePositionCallback)?.getPreferenceAdapterPosition(k) ?: return
        if (position == RecyclerView.NO_POSITION) return
        paint.alpha = (baseAlpha * strength).toInt()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (parent.getChildAdapterPosition(child) != position) continue
            c.drawRect(
                child.left + child.translationX, child.top + child.translationY,
                child.right + child.translationX, child.bottom + child.translationY, paint
            )
        }
    }

    companion object {
        const val HOLD_MS = 1000L
        const val FADE_MS = 400L
    }
}
