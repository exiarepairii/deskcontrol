package com.deskcontrol

/**
 * Small, platform-independent state machine for a display-scoped session.
 *
 * Android can report a display before its window manager accepts windows. The owner uses this
 * state to keep one target across bounded retries, and to reject stale results after a disconnect
 * or target change.
 */
class ExternalDisplaySessionLifecycle<T>(private val maxAttempts: Int) {
    enum class State {
        DISCONNECTED,
        SUSPENDED,
        CONNECTING,
        RETRYING,
        CONNECTED,
        EXHAUSTED
    }

    /**
     * Identity of one attach attempt inside one display-session generation.
     *
     * A display id can survive an OFF/DOZE/ON cycle, so the target alone cannot identify the
     * session that issued an asynchronous attach. Callers must return this token when reporting
     * completion or failure; tokens from a suspended, disconnected, or superseded generation are
     * rejected.
     */
    data class AttemptToken<T>(
        val target: T,
        val generation: Long,
        val attemptNumber: Int
    )

    init {
        require(maxAttempts > 0)
    }

    var state: State = State.DISCONNECTED
        private set

    var target: T? = null
        private set

    var attemptCount: Int = 0
        private set

    var generation: Long = 0L
        private set

    private var attemptInFlight = false

    /** Starts a fresh session generation, even when [newTarget] equals the previous target. */
    fun begin(newTarget: T): Long {
        invalidateGeneration()
        target = newTarget
        attemptCount = 0
        attemptInFlight = false
        state = State.CONNECTING
        return generation
    }

    fun beginAttempt(): AttemptToken<T>? {
        val currentTarget = target ?: return null
        if (state != State.CONNECTING && state != State.RETRYING) return null
        if (attemptInFlight || attemptCount >= maxAttempts) return null
        attemptCount += 1
        attemptInFlight = true
        state = if (attemptCount == 1) State.CONNECTING else State.RETRYING
        return AttemptToken(
            target = currentTarget,
            generation = generation,
            attemptNumber = attemptCount
        )
    }

    fun markAttemptFailed(token: AttemptToken<T>): Boolean {
        if (!isCurrentInFlight(token)) return false
        attemptInFlight = false
        state = if (attemptCount >= maxAttempts) State.EXHAUSTED else State.RETRYING
        return true
    }

    fun markConnected(token: AttemptToken<T>): Boolean {
        if (!isCurrentInFlight(token)) return false
        attemptInFlight = false
        state = State.CONNECTED
        return true
    }

    fun canRetry(): Boolean {
        return target != null &&
            !attemptInFlight &&
            (state == State.CONNECTING || state == State.RETRYING) &&
            attemptCount < maxAttempts
    }

    /**
     * Invalidates the active generation while retaining the target for an ON reconnect.
     * Repeated OFF/DOZE/DOZE_SUSPEND callbacks are idempotent.
     *
     * @return true only when the owner must tear down the active session.
     */
    fun suspend(): Boolean {
        if (state == State.DISCONNECTED || state == State.SUSPENDED) return false
        invalidateGeneration()
        attemptCount = 0
        attemptInFlight = false
        state = State.SUSPENDED
        return true
    }

    /**
     * Invalidates the active generation and forgets the target after physical removal.
     *
     * @return true only for the first transition to disconnected.
     */
    fun disconnect(): Boolean {
        if (state == State.DISCONNECTED) return false
        invalidateGeneration()
        target = null
        attemptCount = 0
        attemptInFlight = false
        state = State.DISCONNECTED
        return true
    }

    private fun isCurrentInFlight(token: AttemptToken<T>): Boolean {
        return attemptInFlight &&
            token.generation == generation &&
            token.attemptNumber == attemptCount &&
            token.target == target &&
            (state == State.CONNECTING || state == State.RETRYING)
    }

    private fun invalidateGeneration() {
        generation += 1L
    }
}
