package com.pianonurmi.etrm.web

import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.DomainException
import com.pianonurmi.etrm.platform.errors.ForbiddenException
import com.pianonurmi.etrm.platform.errors.IllegalStateTransitionException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Maps domain exceptions to RFC 9457 problem details. */
@RestControllerAdvice
class ProblemDetailsHandler {
    private val log = LoggerFactory.getLogger(ProblemDetailsHandler::class.java)

    @ExceptionHandler(DomainException::class)
    fun handleDomain(ex: DomainException): ProblemDetail {
        val status = when (ex) {
            is NotFoundException -> HttpStatus.NOT_FOUND
            is ConflictException, is StaleVersionException, is IllegalStateTransitionException -> HttpStatus.CONFLICT
            is ValidationException -> HttpStatus.UNPROCESSABLE_CONTENT
            is ForbiddenException, is SegregationOfDutiesException -> HttpStatus.FORBIDDEN
        }
        val problem = ProblemDetail.forStatusAndDetail(status, ex.message)
        problem.type = URI.create("urn:etrm:problem:${ex.code}")
        problem.title = ex.code
        if (ex is ValidationException && ex.violations.isNotEmpty()) {
            problem.setProperty("violations", ex.violations)
        }
        return problem
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleBeanValidation(ex: MethodArgumentNotValidException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "Request validation failed")
        problem.type = URI.create("urn:etrm:problem:validation-failed")
        problem.title = "validation-failed"
        problem.setProperty(
            "violations",
            ex.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid")) },
        )
        return problem
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Bad request")
        problem.type = URI.create("urn:etrm:problem:bad-request")
        problem.title = "bad-request"
        return problem
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error("Unhandled exception", ex)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error")
        problem.type = URI.create("urn:etrm:problem:internal")
        problem.title = "internal"
        return problem
    }
}
