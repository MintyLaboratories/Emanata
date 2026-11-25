package minty.emanata.ui

/** Shortening progress badges to their thumbnail, free of Android types. */
object BadgeFit {

    /** The first of [forms], longest first, that [measure] finds no wider than [available]; "" (a bare dot) when none is. */
    fun choose(forms: List<String>, available: Float, measure: (String) -> Float): String =
        forms.firstOrNull { measure(it) <= available } ?: ""

    /** Percent of [pages] read up to zero-based [page], rounded down but at least 1: 40 of 192 is 20. */
    fun percent(page: Int, pages: Int): Int =
        if (pages <= 0) 0 else ((page + 1) * 100L / pages).toInt().coerceIn(1, 100)
}
