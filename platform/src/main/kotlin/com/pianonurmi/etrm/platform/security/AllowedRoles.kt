package com.pianonurmi.etrm.platform.security

/**
 * The roles that may call a REST operation, as its service checks them. It documents rather than enforces: the
 * OpenAPI document publishes it as `x-roles` (ADR 0027), the web UI offers the action only to these roles, and the
 * service still refuses anyone else. An operation without it is open to every signed-in user.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class AllowedRoles(vararg val value: Role)
