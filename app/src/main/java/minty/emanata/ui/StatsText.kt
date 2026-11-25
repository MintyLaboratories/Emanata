package minty.emanata.ui

import android.content.Context
import minty.emanata.R
import minty.emanata.data.ReadingStats

/** The reading statistics dialog's text, streaks counted in days as one or many. */
internal fun Context.statsText(st: ReadingStats): String = getString(
    R.string.stats_body, st.books, st.pages, st.seconds / 3600, (st.seconds % 3600) / 60, st.daysRead,
    resources.getQuantityString(R.plurals.days_count, st.streak, st.streak),
    resources.getQuantityString(R.plurals.days_count, st.bestStreak, st.bestStreak)
)
