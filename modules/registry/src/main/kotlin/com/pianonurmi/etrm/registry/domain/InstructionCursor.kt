package com.pianonurmi.etrm.registry.domain

import com.pianonurmi.etrm.platform.paging.Cursor
import java.time.Instant
import java.util.UUID

/** Keyset position in the instruction list (newest first, then id), opaque to clients. */
data class InstructionCursor(val createdAt: Instant, val id: UUID) {
    fun encode(): String = Cursor.encode(createdAt, id)

    companion object {
        fun decode(cursor: String): InstructionCursor = Cursor.decode(cursor, 2) { (at, id) -> InstructionCursor(Instant.parse(at), UUID.fromString(id)) }
    }
}
