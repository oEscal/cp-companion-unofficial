package pt.cpcompanion.tracking

/** Process-local proof that foreground-service launches are pending or alive. */
object TrackingSessionRegistry {
    private val heartbeatByTicket = LinkedHashMap<String, Long>()

    /**
     * Atomically reserves a launch for one ticket. Different tickets may be tracked concurrently;
     * duplicate launch paths for the same ticket are still rejected.
     */
    @Synchronized
    fun tryClaimLaunch(activeTicketId: String, now: Long = System.currentTimeMillis()): Boolean {
        val heartbeat = heartbeatByTicket[activeTicketId]
        if (heartbeat != null && now - heartbeat <= HEALTHY_HEARTBEAT_WINDOW_MS) return false
        heartbeatByTicket[activeTicketId] = now
        return true
    }

    @Synchronized
    fun markStarted(activeTicketId: String) {
        heartbeatByTicket[activeTicketId] = System.currentTimeMillis()
    }

    @Synchronized
    fun heartbeat(activeTicketId: String) {
        if (activeTicketId in heartbeatByTicket) {
            heartbeatByTicket[activeTicketId] = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun markStopped(activeTicketId: String? = null) {
        if (activeTicketId == null) heartbeatByTicket.clear()
        else heartbeatByTicket.remove(activeTicketId)
    }

    @Synchronized
    fun isRunning(activeTicketId: String, now: Long = System.currentTimeMillis()): Boolean =
        heartbeatByTicket[activeTicketId]?.let { now - it <= HEALTHY_HEARTBEAT_WINDOW_MS } == true

    @Synchronized
    fun runningTicketIds(now: Long = System.currentTimeMillis()): Set<String> =
        heartbeatByTicket.filterValues { now - it <= HEALTHY_HEARTBEAT_WINDOW_MS }.keys.toSet()

    private const val HEALTHY_HEARTBEAT_WINDOW_MS = 4L * 60L * 1000L
}
