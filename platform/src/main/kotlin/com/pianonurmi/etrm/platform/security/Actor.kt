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
    /** Access to every legal entity (granted explicitly, e.g. a `*` scope claim, or implied by ADMIN, AUDITOR and SYSTEM). */
    val allEntities: Boolean = false,
) {
    fun has(role: Role): Boolean = role in roles || Role.ADMIN in roles

    /** Whether the actor may see or act on data of [legalEntityId]; data without an entity is visible to everyone. */
    fun canAccess(legalEntityId: java.util.UUID?): Boolean =
        legalEntityId == null || allEntities || has(Role.AUDITOR) || Role.SYSTEM in roles || legalEntityId in legalEntityScope

    fun requireEntity(legalEntityId: java.util.UUID?) {
        if (!canAccess(legalEntityId)) {
            throw com.pianonurmi.etrm.platform.errors.ForbiddenException("User $userId has no access to legal entity $legalEntityId")
        }
    }

    fun requireRole(vararg allowed: Role) {
        if (allowed.none { has(it) }) {
            throw com.pianonurmi.etrm.platform.errors.ForbiddenException(
                "User $userId lacks any of roles ${allowed.joinToString()}",
            )
        }
    }

    companion object {
        val SYSTEM: Actor = Actor("system", "System", setOf(Role.SYSTEM), allEntities = true)
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
