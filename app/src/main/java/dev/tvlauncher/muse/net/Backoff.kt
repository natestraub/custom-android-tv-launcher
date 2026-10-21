package dev.tvlauncher.muse.net

/**
 * Reconnect delays for the Muse service loop (protocol spec §3.3): 2, 4, 8, 16,
 * 32, 60, 60 … s. Once the edge has refused the VM bearer, [floorMs] keeps
 * retries at least 15 s apart until a healthy session resets it, so a bad
 * bearer is never retried in a tight loop. Not thread-safe; the loop owns it.
 */
class Backoff {
    private var doublings = 0

    /** Lower bound for every delay until [reset]. */
    var floorMs = 0L

    fun nextDelayMs(): Long {
        val delay = minOf(BASE_MS shl doublings, MAX_MS)
        // Saturates long before the cap matters, however long the outage.
        if (doublings < MAX_DOUBLINGS) doublings++
        return maxOf(delay, floorMs)
    }

    fun reset() {
        doublings = 0
        floorMs = 0L
    }

    /**
     * The service's rule after a link session ends: a session registered for
     * 30 s or more starts the sequence over, and an auth failure (upgrade
     * 401/403, `/link-control` 403, register rejected) lifts the floor to 15 s.
     */
    fun afterSession(registeredForMs: Long, authRejected: Boolean): Long {
        if (registeredForMs >= HEALTHY_SESSION_MS) reset()
        if (authRejected) floorMs = AUTH_FLOOR_MS
        return nextDelayMs()
    }

    companion object {
        const val BASE_MS = 2_000L
        const val MAX_MS = 60_000L
        const val AUTH_FLOOR_MS = 15_000L
        const val HEALTHY_SESSION_MS = 30_000L
        private const val MAX_DOUBLINGS = 16
    }
}
