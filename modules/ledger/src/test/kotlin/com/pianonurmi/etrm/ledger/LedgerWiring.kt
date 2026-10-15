package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlInterfaceRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodEndRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqUnpostedEventRepository
import com.pianonurmi.etrm.ledger.api.HardCloseGate
import com.pianonurmi.etrm.ledger.application.HardCloseGates
import com.pianonurmi.etrm.ledger.application.UnpostedEvents
import com.pianonurmi.etrm.platform.tx.Savepoints
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

/** The ledger's collaborators as the application wires them, for the module's hand-wired tests. */
object LedgerWiring {
    /** Hard-close gates; none configured unless a test names them. */
    fun gates(dsl: DSLContext, vararg configured: HardCloseGate) =
        HardCloseGates(JooqJournalRepository(dsl), JooqGlInterfaceRepository(dsl), JooqPeriodEndRepository(dsl), configured.toList())

    /** Posting failures and missing amounts recorded for the ledger's attention, as in the application. */
    fun unposted(dsl: DSLContext, events: ApplicationEventPublisher) = UnpostedEvents(JooqUnpostedEventRepository(dsl), Savepoints.NONE, events)
}
