package `is`.xyz.mpv

import kotlinx.coroutines.Job

/** Provider workers never own publication rights or the filesystem traversal lock. */
internal class ScanOwnership {
    data class Ticket(val generation: Long, val job: Job)
    private var generation = 0L
    private var current: Ticket? = null
    @Synchronized fun begin(job: Job): Ticket {
        val previous = current
        val ticket = Ticket(++generation, job)
        current = ticket
        if (previous?.job !== job) previous?.job?.cancel()
        return ticket
    }
    @Synchronized fun publish(ticket: Ticket, action: () -> Unit): Boolean {
        if (current !== ticket || !ticket.job.isActive) return false
        action()
        return true
    }
    @Synchronized fun finish(ticket: Ticket) { if (current === ticket) current = null }
    @Synchronized fun cancel() { val old = current; current = null; generation++; old?.job?.cancel() }
    @Synchronized fun isActive() = current?.job?.isActive == true
}
