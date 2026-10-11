package com.pianonurmi.etrm.registry.domain

import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Keyset position in the instruction list (newest first, then id), opaque to clients. */
data class InstructionCursor(val createdAt: Instant, val id: UUID) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAt|$id".toByteArray())

    companion object {
        fun decode(cursor: String): InstructionCursor {
            val text = runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull()
            val (at, id) = text?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: throw IllegalArgumentException("Invalid cursor")
            return runCatching { InstructionCursor(Instant.parse(at), UUID.fromString(id)) }.getOrElse { throw IllegalArgumentException("Invalid cursor") }
        }
    }
}
