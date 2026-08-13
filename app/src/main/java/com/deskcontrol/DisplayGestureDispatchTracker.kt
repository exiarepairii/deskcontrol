package com.deskcontrol

/**
 * Counts gesture dispatches within one display-session generation.
 *
 * Android may deliver a gesture callback after an external display has suspended and a new
 * session has already attached to the same display id. A token from the old generation must not
 * decrement the new session's count or release its pending scroll continuation.
 */
class DisplayGestureDispatchTracker {
    data class Token(val generation: Long, val dispatchId: Long)

    var generation: Long = 0L
        private set

    private var nextDispatchId = 0L
    private val activeDispatchIds = mutableSetOf<Long>()

    val inFlightCount: Int
        get() = activeDispatchIds.size

    fun begin(): Token {
        nextDispatchId += 1L
        activeDispatchIds.add(nextDispatchId)
        return Token(generation = generation, dispatchId = nextDispatchId)
    }

    /** Returns false when [token] belongs to a suspended or superseded display session. */
    fun finish(token: Token): Boolean {
        if (token.generation != generation) return false
        return activeDispatchIds.remove(token.dispatchId)
    }

    /** Invalidates every outstanding token and resets the new generation's count. */
    fun invalidate() {
        generation += 1L
        activeDispatchIds.clear()
    }
}
