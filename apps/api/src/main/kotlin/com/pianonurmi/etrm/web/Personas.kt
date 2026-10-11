package com.pianonurmi.etrm.web

import com.pianonurmi.etrm.platform.security.Role

/**
 * A role persona a dev or demo user can act as. The catalogue is the single source for the web UI's persona menu
 * (served in `/api/v1/ui-config`) and for the demo data, so approval tasks land in the inboxes the UI shows.
 */
data class Persona(val id: String, val name: String, val roles: Set<Role>)

object Personas {
    /** Two users per approving role, so four-eyes paths can be walked through in the browser. */
    val ALL: List<Persona> = listOf(
        Persona("trader", "Tia Trader", setOf(Role.TRADER)),
        Persona("trader2", "Theo Trader", setOf(Role.TRADER)),
        Persona("middle", "Mia Middle", setOf(Role.MIDDLE_OFFICE)),
        Persona("middle2", "Max Middle", setOf(Role.MIDDLE_OFFICE)),
        Persona("backoffice", "Bo Backoffice", setOf(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)),
        Persona("accountant", "Ada Accountant", setOf(Role.ACCOUNTANT)),
        Persona("controller", "Cleo Controller", setOf(Role.ACCOUNTANT)),
        Persona("risk", "Rhea Risk", setOf(Role.RISK)),
        Persona("risk2", "Rafe Risk", setOf(Role.RISK)),
        Persona("auditor", "Aud Itor", setOf(Role.AUDITOR)),
        Persona("admin", "Adam Admin", setOf(Role.ADMIN)),
    )

    private val byId = ALL.associateBy { it.id }

    fun find(id: String): Persona? = byId[id]

    fun byId(id: String): Persona = byId[id] ?: throw IllegalArgumentException("Unknown persona $id")
}
