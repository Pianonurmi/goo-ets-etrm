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
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
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

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.mostSpecificCause.message ?: "Malformed request body")
        problem.type = URI.create("urn:etrm:problem:bad-request")
        problem.title = "bad-request"
        return problem
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Bad request")
        problem.type = URI.create("urn:etrm:problem:bad-request")
        problem.title = "bad-request"
        return problem
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parameter '${ex.name}' has an invalid value")
        problem.type = URI.create("urn:etrm:problem:bad-request")
        problem.title = "bad-request"
        problem.setProperty("violations", listOf(mapOf("field" to ex.name, "message" to "invalid value")))
        return problem
    }

    /**
     * Framework errors that already carry a status (unknown path, wrong method, missing parameter, unsupported media
     * type) keep it, so a typo in a URL is a 404 rather than a 500.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        if (ex is ErrorResponse) return frameworkProblem(ex)
        log.error("Unhandled exception", ex)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error")
        problem.type = URI.create("urn:etrm:problem:internal")
        problem.title = "internal"
        return problem
    }

    private fun frameworkProblem(ex: ErrorResponse): ProblemDetail {
        val status = ex.statusCode
        val code = HttpStatus.resolve(status.value())?.name?.lowercase()?.replace('_', '-') ?: "http-${status.value()}"
        val problem = ProblemDetail.forStatusAndDetail(status, ex.body.detail ?: code)
        problem.type = URI.create("urn:etrm:problem:$code")
        problem.title = code
        return problem
    }
}
