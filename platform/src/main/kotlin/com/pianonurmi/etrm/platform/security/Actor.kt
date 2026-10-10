package com.pianonurmi.etrm.platform.security

/**
 * The authenticated principal acting on the system. Carried into domain services so that audit trails,
 * four-eyes approvals and segregation-of-duties checks never depend on web-layer types.
 */
data class Actor(
    val userId: String,
    val displayName: String,
    val roles: Set<Role>,
    val legalEntityScope: Set<java.util.UUID> = emptySet(),
) {
    fun has(role: Role): Boolean = role in roles || Role.ADMIN in roles

    fun requireRole(vararg allowed: Role) {
        if (allowed.none { has(it) }) {
            throw com.pianonurmi.etrm.platform.errors.ForbiddenException(
                "User $userId lacks any of roles ${allowed.joinToString()}",
            )
        }
    }

    companion object {
        val SYSTEM: Actor = Actor("system", "System", setOf(Role.SYSTEM))
    }
}

/** Functional roles. Segregation of duties is enforced in the workflow and approval services. */
enum class Role {
    TRADER,
    MIDDLE_OFFICE,
    BACK_OFFICE,
    REGISTRY_OPERATOR,
    ACCOUNTANT,
    RISK,
    AUDITOR,
    ADMIN,
    SYSTEM,
}

/** Resolves the current actor from the execution context (security context, job runner, test). */
fun interface ActorProvider {
    fun current(): Actor
}
