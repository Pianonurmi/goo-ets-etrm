package com.pianonurmi.etrm.document.application

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Renders PDFs whose after-commit render was lost (a restart) or failed, a few times before giving up. */
@Component
@EnableConfigurationProperties(DocumentLimits::class)
class DocumentRenderJob(private val documents: DocumentService) {
    private val log = LoggerFactory.getLogger(DocumentRenderJob::class.java)

    @Scheduled(fixedDelayString = "\${etrm.documents.render-delay:PT1M}", initialDelayString = "\${etrm.documents.render-initial-delay:PT1M}")
    @SchedulerLock(name = "document-render", lockAtMostFor = "PT10M")
    fun run() {
        val rendered = documents.renderDue()
        if (rendered > 0) log.info("Rendered {} pending documents", rendered)
    }
}
