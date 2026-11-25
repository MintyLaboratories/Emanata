package minty.emanata.ui

/**
 * How fast a grid fills in, in milliseconds from [start]: until its first image, until
 * every cell in view shows at least a stand-in, and until every cell in view shows its
 * final image. Each is -1 until reached.
 */
class FillTiming(private val start: Long) {
    private val shown = HashSet<Int>()
    private val final = HashSet<Int>()

    var first = -1L
        private set
    var inView = -1L
        private set
    var exact = -1L
        private set

    /** True once every cell in view showed its final image. */
    val done: Boolean get() = exact >= 0

    /**
     * Cell [position] shows an image at [now], its final one when [isFinal], while
     * [visible] cells are in view. True when this completes the grid in view.
     */
    fun shown(position: Int, isFinal: Boolean, visible: IntRange, now: Long): Boolean {
        if (done) return false
        shown += position
        if (isFinal) final += position
        if (first < 0) first = now - start
        if (visible.isEmpty()) return false
        if (inView < 0 && visible.all { it in shown }) inView = now - start
        if (visible.all { it in final }) {
            exact = now - start
            return true
        }
        return false
    }
}
