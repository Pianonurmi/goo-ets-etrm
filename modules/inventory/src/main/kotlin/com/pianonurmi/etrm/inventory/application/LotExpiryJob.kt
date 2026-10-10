package com.pianonurmi.etrm.inventory.application

import com.pianonurmi.etrm.inventory.api.LotCommand
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/** Nightly sweep marking lots past their expiry date; locked across instances with ShedLock. */
@Component
class LotExpiryJob(private val lots: LotCommand, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(LotExpiryJob::class.java)

    @Scheduled(cron = "\${etrm.inventory.expiry-cron:0 30 0 * * *}")
    @SchedulerLock(name = "inventory-lot-expiry", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    fun run() {
        val today = LocalDate.now(clock)
        val expired = lots.expireDueLots(today)
        if (expired > 0) log.info("Expired {} certificate lots as of {}", expired, today)
    }
}
