package com.pianonurmi.etrm.allocation.application

import com.pianonurmi.etrm.allocation.api.AllocationCommand
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/** Expires proposals whose soft reservation lapsed so their lots return to the pool. */
@Component
class AllocationJobs(private val allocation: AllocationCommand, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(AllocationJobs::class.java)

    @Scheduled(cron = "\${etrm.allocation.expiry-cron:0 */15 * * * *}")
    @SchedulerLock(name = "allocation-soft-reservation-expiry", lockAtMostFor = "PT10M")
    fun expireProposals() {
        val expired = allocation.expireStalePlans(clock.instant())
        if (expired > 0) log.info("Expired {} allocation proposals", expired)
    }
}
