package com.pianonurmi.etrm.registry.fixtures

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingCommand
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.BeneficiaryCommand
import com.pianonurmi.etrm.inventory.api.InTransferLotLeftAccount
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.registry.adapter.jooq.JooqRegistryInstructionRepository
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.RegistryInstructionQuery
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.application.InstructionCommands
import com.pianonurmi.etrm.registry.application.InstructionExecution
import com.pianonurmi.etrm.registry.application.InstructionFactory
import com.pianonurmi.etrm.registry.application.InstructionOutcomes
import com.pianonurmi.etrm.registry.application.InstructionQueries
import com.pianonurmi.etrm.registry.application.InstructionStore
import com.pianonurmi.etrm.registry.application.InstructionSubmission
import com.pianonurmi.etrm.registry.application.RegistryDispatchSettings
import com.pianonurmi.etrm.registry.application.RegistryInstructions
import com.pianonurmi.etrm.registry.application.TransferRouteService
import com.pianonurmi.etrm.registry.domain.RegistryGateway
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** The instruction services, wired as the application wires them: the module's API, and the parts tests drive. */
class WiredInstructions(
    submission: InstructionSubmission,
    val commands: InstructionCommands,
    val outcomes: InstructionOutcomes,
    queries: InstructionQueries,
) : RegistryInstructionCommand by RegistryInstructions(submission, commands, outcomes),
    RegistryInstructionQuery by queries {
    fun approve(instructionId: UUID, by: String): RegistryInstructionView = commands.approve(instructionId, by)

    fun flagOverdue(now: Instant): Int = outcomes.flagOverdue(now)

    fun on(event: InTransferLotLeftAccount) = outcomes.on(event)
}

/** Registry instructions as the application wires them, for hand-wired tests of any module. */
object RegistryWiring {
    @Suppress("LongParameterList") // the services' own collaborators, each passed by the test
    fun instructions(
        dsl: DSLContext,
        gateways: List<RegistryGateway>,
        lots: LotQuery,
        lotCommand: LotCommand,
        referenceData: ReferenceDataQuery,
        routes: TransferRouteService,
        actor: ActorProvider,
        events: ApplicationEventPublisher,
        clock: Clock,
        allowances: AllowanceHoldingCommand,
        allowanceQuery: AllowanceHoldingQuery,
        beneficiaries: BeneficiaryCommand,
        settings: RegistryDispatchSettings = RegistryDispatchSettings(),
    ): WiredInstructions {
        val repository = JooqRegistryInstructionRepository(dsl)
        val store = InstructionStore(repository, clock)
        val outcomes = InstructionOutcomes(repository, store, lotCommand, allowances, referenceData, actor, events, settings)
        val execution = InstructionExecution(gateways, outcomes)
        val factory = InstructionFactory(lots, lotCommand, referenceData, allowances, allowanceQuery, actor, store)
        return WiredInstructions(
            InstructionSubmission(repository, factory, execution, routes, beneficiaries, referenceData, lotCommand, events, store, settings),
            InstructionCommands(store, execution, lotCommand, lots, allowances, events, settings),
            outcomes,
            InstructionQueries(repository, actor),
        )
    }
}
