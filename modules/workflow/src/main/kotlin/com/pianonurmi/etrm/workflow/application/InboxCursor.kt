package com.pianonurmi.etrm.workflow.application

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.InboxSort
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Keyset pagination cursor: the sort key values of the last row of a page, so the next page starts after it even
 * while tasks are being added or closed. Encoded as URL-safe base64 of `sort|value...|id`.
 */
data class InboxCursor(val sort: InboxSort, val priority: Int?, val instant: Instant, val id: UUID) {
    companion object {
        fun encode(sort: InboxSort, last: ApprovalTaskView): String {
            val parts = when (sort) {
                InboxSort.DUE -> listOf(sort.name, "", last.dueAt.toString(), last.id.toString())
                InboxSort.REQUESTED -> listOf(sort.name, "", last.requestedAt.toString(), last.id.toString())
                InboxSort.PRIORITY -> listOf(sort.name, last.priority.toString(), last.dueAt.toString(), last.id.toString())
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(parts.joinToString("|").toByteArray())
        }

        fun decode(cursor: String, expected: InboxSort): InboxCursor = try {
            val parts = String(Base64.getUrlDecoder().decode(cursor)).split("|")
            val sort = InboxSort.valueOf(parts[0])
            if (sort != expected) throw ValidationException("Cursor was issued for sort $sort, not $expected")
            InboxCursor(sort, parts[1].takeIf { it.isNotEmpty() }?.toInt(), Instant.parse(parts[2]), UUID.fromString(parts[3]))
        } catch (ex: ValidationException) {
            throw ex
        } catch (ex: RuntimeException) {
            throw ValidationException("Invalid cursor: ${ex.message}")
        }
    }
}
