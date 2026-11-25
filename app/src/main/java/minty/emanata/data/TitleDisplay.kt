package minty.emanata.data

/** How entry titles show in the library. */
enum class TitleDisplay(val key: String) {
    HIDDEN("hidden"),

    /** One line, cut with "…" at the end. */
    SINGLE("single"),

    /** One line, cut with "…" in the middle, so the end (year, number) stays. */
    MIDDLE("middle"),

    /** Up to two lines, cut with "…" at the end. */
    DOUBLE("double"),

    /** One line; an overflowing title glides to its end, pauses, and glides back. */
    BOUNCE("bounce"),

    /** One line; an overflowing title loops leftward. */
    MARQUEE("marquee");

    val animated: Boolean get() = this == BOUNCE || this == MARQUEE

    companion object {
        fun of(key: String?): TitleDisplay = entries.firstOrNull { it.key == key } ?: DOUBLE
    }
}
