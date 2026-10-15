package com.pianonurmi.etrm.workflow.application

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.paging.Cursor
import com.pianonurmi.etrm.workflow.api.ApprovalTaskView
import com.pianonurmi.etrm.workflow.api.InboxSort
import java.time.Instant
import java.util.UUID

/**
 * Keyset pagination cursor: the sort key values of the last row of a page, so the next page starts after it even
 * while tasks are being added or closed: `sort|priority|instant|id` through the platform [Cursor].
 */
data class InboxCursor(val sort: InboxSort, val priority: Int?, val instant: Instant, val id: UUID) {
    companion object {
        fun encode(sort: InboxSort, last: ApprovalTaskView): String = when (sort) {
            InboxSort.DUE -> Cursor.encode(sort, null, last.dueAt, last.id)
            InboxSort.REQUESTED -> Cursor.encode(sort, null, last.requestedAt, last.id)
            InboxSort.PRIORITY -> Cursor.encode(sort, last.priority, last.dueAt, last.id)
        }

        fun decode(cursor: String, expected: InboxSort): InboxCursor = Cursor.decode(cursor, PARTS) { parts ->
            val issued = InboxSort.valueOf(parts[0])
            if (issued != expected) throw ValidationException("Cursor was issued for sort $issued, not $expected")
            InboxCursor(issued, parts[1].takeIf { it.isNotEmpty() }?.toInt(), Instant.parse(parts[2]), UUID.fromString(parts[3]))
        }

        private const val PARTS = 4
    }
}
