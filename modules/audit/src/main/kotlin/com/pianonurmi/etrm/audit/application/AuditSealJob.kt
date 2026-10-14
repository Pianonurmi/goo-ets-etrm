package com.pianonurmi.etrm.audit.application

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Seals new audit rows every minute, so a change to the trail is evident within minutes of the event. */
@Component
class AuditSealJob(private val audit: AuditService) {
    private val log = LoggerFactory.getLogger(AuditSealJob::class.java)

    @Scheduled(fixedDelayString = "\${etrm.audit.seal-delay:PT1M}", initialDelayString = "\${etrm.audit.seal-initial-delay:PT1M}")
    @SchedulerLock(name = "audit-seal", lockAtMostFor = "PT10M")
    fun run() {
        val sealed = audit.seal()
        if (sealed > 0) log.debug("Sealed {} audit rows", sealed)
    }
}
