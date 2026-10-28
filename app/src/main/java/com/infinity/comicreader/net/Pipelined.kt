package com.infinity.comicreader.net

/**
 * Fills a range of a file with several requests in flight at once, each for up to a
 * piece of it, answered in order: while one answer streams in, the next requests are
 * already on their way, so the link never idles between answers for a round trip or
 * for the server to start the next. How many go at once is bounded by a depth and by
 * what the [Source] has room for (an SMB server's credits).
 */
internal object Pipelined {
    /** A request under way: its bytes, waited for up to a time; fewer than asked only at the end, none past it. */
    fun interface Pending {
        fun await(timeoutMs: Long): ByteArray
    }

    /** Where the pieces come from. */
    interface Source {
        /** Asks for up to [length] bytes at [position]; the answer is awaited later. */
        fun start(position: Long, length: Int): Pending

        /** How many more requests of [length] bytes may start while others are under way. */
        fun room(length: Int): Int = Int.MAX_VALUE
    }

    /**
     * Fills [buffer] from [offset] with up to [count] bytes at [position] from [source],
     * in requests of up to [piece] bytes, at most [depth] under way; [heard] is told of
     * each answer. Returns the bytes filled, from [position] on without a gap: fewer
     * than [count] at the end of the file, or when a request after the first failed or
     * came back short (the requests after it are let go); -1 when the first finds
     * nothing. A failure of the first request is thrown.
     */
    fun fill(
        source: Source,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        count: Int,
        piece: Int,
        depth: Int,
        timeoutMs: Long,
        heard: () -> Unit = {}
    ): Int {
        require(piece > 0 && depth > 0) { "Piece of $piece bytes, $depth deep" }
        if (count <= 0) return 0
        val queue = ArrayDeque<Pair<Int, Pending>>()
        var asked = 0
        var filled = 0
        while (filled < count) {
            while (asked < count && queue.size < depth && (queue.isEmpty() || source.room(minOf(piece, count - asked)) > 0)) {
                val length = minOf(piece, count - asked)
                queue.addLast(length to source.start(position + asked, length))
                asked += length
            }
            val (length, pending) = queue.removeFirst()
            val bytes = try {
                pending.await(timeoutMs)
            } catch (e: Exception) {
                if (filled > 0) return filled
                throw e
            }
            heard()
            val n = minOf(bytes.size, length)
            System.arraycopy(bytes, 0, buffer, offset + filled, n)
            filled += n
            if (n < length) break
        }
        return if (filled == 0) -1 else filled
    }
}
