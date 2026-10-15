package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlAccountRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlExportRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqGlInterfaceRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqJournalRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodEndRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPeriodRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqPostingRuleRepository
import com.pianonurmi.etrm.ledger.adapter.jooq.JooqUnpostedEventRepository
import com.pianonurmi.etrm.ledger.api.HardCloseGate
import com.pianonurmi.etrm.ledger.application.HardCloseGates
import com.pianonurmi.etrm.ledger.application.LedgerService
import com.pianonurmi.etrm.ledger.application.UnpostedEvents
import com.pianonurmi.etrm.marketdata.api.FxRates
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.tx.Savepoints
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock

/** The ledger's collaborators as the application wires them, for the module's hand-wired tests. */
object LedgerWiring {
    /** Hard-close gates; none configured unless a test names them. */
    fun gates(dsl: DSLContext, vararg configured: HardCloseGate) =
        HardCloseGates(JooqJournalRepository(dsl), JooqGlInterfaceRepository(dsl), JooqPeriodEndRepository(dsl), configured.toList())

    /** Posting failures and missing amounts recorded for the ledger's attention, as in the application. */
    fun unposted(dsl: DSLContext, events: ApplicationEventPublisher) = UnpostedEvents(JooqUnpostedEventRepository(dsl), Savepoints.NONE, events)

    /** The ledger on the module's repositories, with no hard-close gates configured. */
    @Suppress("LongParameterList") // the service's collaborators that tests vary
    fun ledgerService(
        dsl: DSLContext,
        fx: FxRates,
        referenceData: ReferenceDataQuery,
        actor: ActorProvider,
        events: ApplicationEventPublisher,
        clock: Clock,
        unposted: UnpostedEvents = unposted(dsl, events),
    ) = LedgerService(
        JooqGlAccountRepository(dsl),
        JooqPostingRuleRepository(dsl),
        JooqJournalRepository(dsl),
        JooqPeriodRepository(dsl),
        fx,
        JooqGlExportRepository(dsl),
        referenceData,
        actor,
        events,
        clock,
        JooqGlInterfaceRepository(dsl),
        gates(dsl),
        unposted,
    )
}
