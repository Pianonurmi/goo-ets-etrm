package com.pianonurmi.etrm.platform.errors

/**
 * Domain error hierarchy. REST adapters map these to RFC 9457 problem details; everything else is a 500.
 */
sealed class DomainException(message: String, val code: String, cause: Throwable? = null) : RuntimeException(message, cause)

class NotFoundException(entity: String, id: Any) : DomainException("$entity $id not found", "not-found")

class ConflictException(message: String) : DomainException(message, "conflict")

class ValidationException(message: String, val violations: List<Violation> = emptyList()) :
    DomainException(message, "validation-failed")

class ForbiddenException(message: String) : DomainException(message, "forbidden")

class IllegalStateTransitionException(aggregate: String, from: String, to: String) :
    DomainException("$aggregate cannot transition from $from to $to", "illegal-state-transition")

class StaleVersionException(entity: String, id: Any, expected: Int, actual: Int) :
    DomainException("$entity $id was modified concurrently (expected version $expected, found $actual)", "stale-version")

class SegregationOfDutiesException(message: String) : DomainException(message, "segregation-of-duties")

data class Violation(val field: String, val message: String)

inline fun <T> requireValid(value: Boolean, lazyMessage: () -> String): T? {
    if (!value) throw ValidationException(lazyMessage())
    return null
}
