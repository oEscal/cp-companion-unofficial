package pt.cpcompanion.tracking

/** Process-local proof that a foreground-service launch is pending or the service is alive. */
object TrackingSessionRegistry {
    @Volatile
    private var ticketId: String? = null

    @Volatile
    private var heartbeatEpochMillis: Long = 0L

    /**
     * Atomically reserves a launch in this process. This closes the small race where an alarm and
     * reconciliation worker can both call startForegroundService before onStartCommand runs.
     */
    @Synchronized
    fun tryClaimLaunch(activeTicketId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (ticketId == activeTicketId && now - heartbeatEpochMillis <= HEALTHY_HEARTBEAT_WINDOW_MS) {
            return false
        }
        ticketId = activeTicketId
        heartbeatEpochMillis = now
        return true
    }

    @Synchronized
    fun markStarted(activeTicketId: String) {
        ticketId = activeTicketId
        heartbeatEpochMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun heartbeat(activeTicketId: String) {
        if (ticketId == activeTicketId) heartbeatEpochMillis = System.currentTimeMillis()
    }

    @Synchronized
    fun markStopped(activeTicketId: String? = null) {
        if (activeTicketId == null || ticketId == activeTicketId) {
            ticketId = null
            heartbeatEpochMillis = 0L
        }
    }

    fun isRunning(activeTicketId: String, now: Long = System.currentTimeMillis()): Boolean =
        ticketId == activeTicketId && now - heartbeatEpochMillis <= HEALTHY_HEARTBEAT_WINDOW_MS

    private const val HEALTHY_HEARTBEAT_WINDOW_MS = 4L * 60L * 1000L
}
