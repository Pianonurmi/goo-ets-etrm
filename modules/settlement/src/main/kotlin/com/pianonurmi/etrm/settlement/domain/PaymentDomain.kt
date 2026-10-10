package com.pianonurmi.etrm.settlement.domain

import com.pianonurmi.etrm.settlement.api.AllocationView
import com.pianonurmi.etrm.settlement.api.NettingStatementView
import com.pianonurmi.etrm.settlement.api.PaymentView
import java.math.BigDecimal
import java.util.UUID

interface PaymentRepository {
    fun insert(payment: PaymentView)

    fun find(id: UUID, forUpdate: Boolean = false): PaymentView?

    fun byReference(legalEntityId: UUID, bankReference: String): PaymentView?

    fun updateUnapplied(id: UUID, unapplied: BigDecimal)

    fun list(legalEntityId: UUID, counterpartyId: UUID?, unappliedOnly: Boolean): List<PaymentView>
}

interface AllocationRepository {
    fun insert(allocation: AllocationView)

    fun forInvoice(invoiceId: UUID): List<AllocationView>
}

interface NettingRepository {
    fun insert(statement: NettingStatementView)

    fun update(statement: NettingStatementView)

    fun find(id: UUID, forUpdate: Boolean = false): NettingStatementView?

    fun list(legalEntityId: UUID, counterpartyId: UUID?): List<NettingStatementView>

    /** Invoices already in a statement that is not cancelled or settled. */
    fun invoicesInOpenStatements(legalEntityId: UUID, counterpartyId: UUID): Set<UUID>
}
