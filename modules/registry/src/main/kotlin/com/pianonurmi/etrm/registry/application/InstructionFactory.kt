package com.pianonurmi.etrm.registry.application

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingCommand
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.BeneficiaryCommand
import com.pianonurmi.etrm.inventory.api.InTransferLotLeftAccount
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.api.RecordAllowanceMovement
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.SecondPerson
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.referencedata.api.HolderType
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryAccountView
import com.pianonurmi.etrm.referencedata.api.RegistryType
import com.pianonurmi.etrm.registry.api.AssetKind
import com.pianonurmi.etrm.registry.api.InstructionCancelled
import com.pianonurmi.etrm.registry.api.InstructionConfirmed
import com.pianonurmi.etrm.registry.api.InstructionFailed
import com.pianonurmi.etrm.registry.api.InstructionOutcomeUnknown
import com.pianonurmi.etrm.registry.api.InstructionOverdue
import com.pianonurmi.etrm.registry.api.InstructionPage
import com.pianonurmi.etrm.registry.api.InstructionSearch
import com.pianonurmi.etrm.registry.api.InstructionStatus
import com.pianonurmi.etrm.registry.api.InstructionSubmitted
import com.pianonurmi.etrm.registry.api.InstructionType
import com.pianonurmi.etrm.registry.api.RegistryInstructionCommand
import com.pianonurmi.etrm.registry.api.RegistryInstructionQuery
import com.pianonurmi.etrm.registry.api.RegistryInstructionView
import com.pianonurmi.etrm.registry.api.SubmitAllowanceTransfer
import com.pianonurmi.etrm.registry.api.SubmitCancellation
import com.pianonurmi.etrm.registry.api.SubmitSurrender
import com.pianonurmi.etrm.registry.api.SubmitTransfer
import com.pianonurmi.etrm.registry.domain.GatewayResult
import com.pianonurmi.etrm.registry.domain.RegistryGateway
import com.pianonurmi.etrm.registry.domain.RegistryInstruction
import com.pianonurmi.etrm.registry.domain.RegistryInstructionRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Checks an instruction's source (account, lot, allowance holding), takes hold of the asset and builds the instruction. */
@Component
class InstructionFactory(
    private val lotQuery: LotQuery,
    private val lots: LotCommand,
    private val referenceData: ReferenceDataQuery,
    private val allowances: AllowanceHoldingCommand,
    private val allowanceQuery: AllowanceHoldingQuery,
    private val actorProvider: ActorProvider,
    private val store: InstructionStore,
) {
    /** The account the lot is held on, usable for the instruction. */
    fun requireUsableLotAccount(lotId: UUID, export: Boolean): RegistryAccountView = requireUsableAccount(lotQuery.requireLot(lotId).registryAccountId, export)

    /** The source account must be active and, for transfers, allowed to export. */
    fun requireUsableAccount(accountId: UUID, export: Boolean): RegistryAccountView {
        val account = referenceData.requireRegistryAccount(accountId)
        if (!account.active) throw ValidationException("Registry account ${account.accountNumber} is inactive")
        if (export && !account.canExport) throw ValidationException("Registry account ${account.accountNumber} may not export")
        return account
    }

    /** One of our accounts in an emission allowance registry. */
    fun allowanceAccount(accountId: UUID): RegistryAccountView {
        val account = requireUsableAccount(accountId, export = true)
        if (account.holderType != HolderType.LEGAL_ENTITY) throw ValidationException("Account ${account.accountNumber} is not one of ours")
        if (referenceData.requireRegistry(account.registryCode).registryType != RegistryType.EMISSION_ALLOWANCE) {
            throw ValidationException("Account ${account.accountNumber} is not an emission allowance account")
        }
        return account
    }

    fun reserveAllowances(accountId: UUID, productCode: String, quantity: Long): AllowanceHoldingView {
        val holding = allowanceQuery.holding(accountId, productCode.uppercase())
            ?: throw ValidationException("No ${productCode.uppercase()} holding on account $accountId")
        return allowances.reserve(holding.id, quantity)
    }

    fun allowanceInstruction(type: InstructionType, holding: AllowanceHoldingView, key: String, quantity: Long): RegistryInstructionView {
        val account = referenceData.requireRegistryAccount(holding.registryAccountId)
        return RegistryInstructionView(
            id = Ids.newId(),
            instructionKey = key,
            type = type,
            registryCode = account.registryCode,
            holderEntityId = holding.holderEntityId,
            fromAccountId = holding.registryAccountId,
            toRegistryCode = null,
            toAccountRef = null,
            lotId = null,
            range = null,
            quantity = quantity,
            cancellation = null,
            planRef = null,
            lineRef = null,
            obligationRef = null,
            status = InstructionStatus.PENDING,
            externalRef = null,
            attempts = 0,
            lastError = null,
            submittedBy = actorProvider.current().userId,
            confirmedBy = null,
            createdAt = store.now(),
            sentAt = null,
            confirmedAt = null,
            version = 1,
            assetKind = AssetKind.ALLOWANCE,
            productCode = holding.productCode,
            holdingRef = holding.id,
        )
    }

    fun prepareLot(lotId: UUID, quantity: Long?): LotView {
        val lot = lotQuery.requireLot(lotId)
        if (lot.status != LotStatus.HELD && lot.status != LotStatus.RESERVED) throw ConflictException("Lot $lotId is ${lot.status}; cannot instruct")
        if (quantity == null || quantity == lot.quantityMwh) return lot
        if (quantity > lot.quantityMwh) throw ValidationException("Quantity $quantity exceeds lot of ${lot.quantityMwh} MWh")
        val carved = lots.split(lotId, quantity).carved
        return if (lot.status == LotStatus.RESERVED) lotQuery.requireLot(carved.id) else carved
    }

    fun newInstruction(type: InstructionType, lot: LotView, key: String, planRef: UUID?, lineRef: UUID?, obligationRef: UUID?) =
        RegistryInstructionView(
            id = Ids.newId(),
            instructionKey = key,
            type = type,
            registryCode = lot.registryCode,
            holderEntityId = lot.holderEntityId,
            fromAccountId = lot.registryAccountId,
            toRegistryCode = null,
            toAccountRef = null,
            lotId = lot.id,
            range = lot.range,
            quantity = lot.quantityMwh,
            cancellation = null,
            planRef = planRef,
            lineRef = lineRef,
            obligationRef = obligationRef,
            status = InstructionStatus.PENDING,
            externalRef = null,
            attempts = 0,
            lastError = null,
            submittedBy = actorProvider.current().userId,
            confirmedBy = null,
            createdAt = store.now(),
            sentAt = null,
            confirmedAt = null,
            version = 1,
        )
}
