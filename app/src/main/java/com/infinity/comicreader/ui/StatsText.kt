package com.infinity.comicreader.ui

import android.content.Context
import com.infinity.comicreader.R
import com.infinity.comicreader.data.ReadingStats

/** The reading statistics dialog's text, streaks counted in days as one or many. */
internal fun Context.statsText(st: ReadingStats): String = getString(
    R.string.stats_body, st.chapters, st.pages, st.seconds / 3600, (st.seconds % 3600) / 60, st.daysRead,
    resources.getQuantityString(R.plurals.days_count, st.streak, st.streak),
    resources.getQuantityString(R.plurals.days_count, st.bestStreak, st.bestStreak)
)
