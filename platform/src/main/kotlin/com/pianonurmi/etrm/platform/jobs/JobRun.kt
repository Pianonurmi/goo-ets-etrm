package com.pianonurmi.etrm.platform.jobs

/**
 * What the scheduled job running on this thread did, for its metrics (`etrm.jobs.*`, review finding O2). The
 * application opens a run around every `@Scheduled` method; a job that works through subjects one by one and keeps
 * going after one fails reports each failure with [subjectFailed] (after logging it with its stack trace), so the
 * failure is counted although the run itself completes. Outside a scheduled run the calls do nothing.
 */
object JobRun {
    /** How a run ended: whether this instance held the job's lock (else it skipped the run) and how many subjects failed. */
    data class Outcome(val ran: Boolean, val subjectFailures: Int)

    private class State(var ran: Boolean, var subjectFailures: Int = 0)

    private val current = ThreadLocal<State?>()

    /** Opens a run; [locked] is false for a job with a cluster lock until the lock is acquired. */
    fun begin(locked: Boolean) = current.set(State(ran = !locked))

    /** The job's cluster lock was acquired: this instance runs the job. */
    fun lockAcquired() {
        current.get()?.ran = true
    }

    fun subjectFailed() {
        current.get()?.let { it.subjectFailures++ }
    }

    fun end(): Outcome? = current.get()?.let { Outcome(it.ran, it.subjectFailures) }.also { current.remove() }
}
