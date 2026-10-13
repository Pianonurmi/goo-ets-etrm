package com.pianonurmi.etrm.clearing.domain

import com.pianonurmi.etrm.clearing.api.MarginCallStatus
import com.pianonurmi.etrm.clearing.api.MarginCallView
import java.util.UUID

interface MarginCallRepository {
    fun insert(call: MarginCallView)

    fun update(call: MarginCallView)

    /** The account's open call, locked. */
    fun openCall(accountId: UUID): MarginCallView?

    fun calls(legalEntityId: UUID?, status: MarginCallStatus?): List<MarginCallView>

    fun call(id: UUID, lock: Boolean = false): MarginCallView?
}
