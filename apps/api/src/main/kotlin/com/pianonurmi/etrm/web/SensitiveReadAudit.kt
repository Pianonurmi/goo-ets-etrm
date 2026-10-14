package com.pianonurmi.etrm.web

import com.pianonurmi.etrm.audit.api.AuditCommand
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Records who read sensitive data (review finding S6): a document's content, a party's bank accounts, a report
 * export or the invoice export, and the audit trail itself. Writes leave their own trace through domain events;
 * these reads would otherwise leave none. Only successful reads are recorded, after the response; a failure to record
 * is logged as an error rather than failing a read that has already been served.
 */
class SensitiveReadAudit(private val audit: ObjectProvider<AuditCommand>) : HandlerInterceptor {
    private val log = LoggerFactory.getLogger(SensitiveReadAudit::class.java)

    override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
        if (request.method != "GET" || ex != null || response.status >= MIN_ERROR_STATUS) return
        val path = request.servletPath + request.pathInfo.orEmpty()
        val (type, ref) = classify(path) ?: return
        runCatching {
            audit.ifAvailable?.record(EVENT_TYPE, type, ref, mapOf("path" to path, "query" to request.queryString, "status" to response.status))
        }.onFailure { log.error("The read of {} could not be recorded in the audit trail", path, it) }
    }

    companion object {
        const val EVENT_TYPE = "SensitiveRead"
        private const val MIN_ERROR_STATUS = 400
        private const val ID = "([0-9a-fA-F-]{36})"
        private val RULES = listOf(
            Regex("^/api/v1/documents/$ID/content$") to "Document",
            Regex("^/api/v1/reference/parties/[A-Z_]+/$ID/bank-accounts$") to "BankAccounts",
            Regex("^/api/v1/reports/exports/$ID(/.*)?$") to "ReportExport",
            Regex("^/api/v1/settlement/invoices/export$") to "InvoiceExport",
            Regex("^/api/v1/audit/events(/$ID)?$") to "AuditTrail",
        )

        /** The aggregate type and reference of a sensitive read, or null when [path] is not one. */
        fun classify(path: String): Pair<String, String?>? = RULES.firstNotNullOfOrNull { (pattern, type) ->
            pattern.matchEntire(path)?.let { match -> type to match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } }
        }
    }
}

@Configuration
class SensitiveReadAuditConfiguration(private val audit: ObjectProvider<AuditCommand>) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(SensitiveReadAudit(audit)).addPathPatterns("/api/**")
    }
}
