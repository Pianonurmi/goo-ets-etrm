package com.pianonurmi.etrm.inventory.application

import com.pianonurmi.etrm.inventory.api.AllowanceHoldingCommand
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingQuery
import com.pianonurmi.etrm.inventory.api.AllowanceHoldingView
import com.pianonurmi.etrm.inventory.api.AllowanceMovementType
import com.pianonurmi.etrm.inventory.api.AllowanceMovementView
import com.pianonurmi.etrm.inventory.api.HoldingChanged
import com.pianonurmi.etrm.inventory.api.RecordAllowanceMovement
import com.pianonurmi.etrm.inventory.domain.AllowanceHoldingRepository
import com.pianonurmi.etrm.inventory.domain.NewAllowanceMovement
import com.pianonurmi.etrm.platform.errors.ConflictException
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.product.api.ProductCatalogueQuery
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryType
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class AllowanceHoldingService(
    private val repository: AllowanceHoldingRepository,
    private val referenceData: ReferenceDataQuery,
    private val catalogue: ProductCatalogueQuery,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
) : AllowanceHoldingCommand, AllowanceHoldingQuery {
    override fun record(command: RecordAllowanceMovement): AllowanceHoldingView {
        val account = referenceData.requireRegistryAccount(command.registryAccountId)
        if (referenceData.requireRegistry(account.registryCode).registryType != RegistryType.EMISSION_ALLOWANCE) {
            throw ValidationException("Account ${account.accountNumber} is not an emission allowance account")
        }
        if (account.holderId != command.holderEntityId) throw ValidationException("Account ${account.accountNumber} is not held by ${command.holderEntityId}")
        val product = catalogue.allowanceProduct(command.productCode) ?: throw NotFoundException("AllowanceProduct", command.productCode)
        if (command.type != AllowanceMovementType.CORRECTION && command.quantity <= 0) throw ValidationException("Quantity must be positive")
        if (command.type == AllowanceMovementType.CORRECTION && command.quantity == 0L) throw ValidationException("Correction must be non-zero")
        val signed = if (command.type == AllowanceMovementType.CORRECTION) command.quantity else command.type.sign * command.quantity

        val holding = repository.lockOrCreate(command.holderEntityId, account.id, product.code)
        val newQuantity = holding.quantity + signed
        if (newQuantity < 0) throw ConflictException("Holding ${holding.id} has ${holding.quantity} ${product.code}; cannot move $signed")
        if (newQuantity < holding.reservedQuantity) {
            throw ConflictException(
                "Holding ${holding.id} has ${holding.reservedQuantity} reserved; cannot move $signed",
            )
        }
        val updated = holding.copy(quantity = newQuantity, version = holding.version + 1)
        if (!repository.update(updated)) throw ConflictException("Holding ${holding.id} changed concurrently")
        val movement = repository.appendMovement(
            NewAllowanceMovement(
                holdingId = holding.id,
                type = command.type.name,
                signedQuantity = signed,
                effectiveDate = command.effectiveDate,
                reference = command.reference,
                costPerUnit = command.costPerUnit,
                costCurrency = command.costCurrency,
                complianceYear = command.complianceYear,
                actor = actorProvider.current().userId,
                correlationId = null,
            ),
        )
        events.publishEvent(HoldingChanged(updated, movement))
        return updated
    }

    override fun reserve(holdingId: UUID, quantity: Long): AllowanceHoldingView {
        val holding = repository.findById(holdingId) ?: throw NotFoundException("AllowanceHolding", holdingId)
        if (quantity <= 0) throw ValidationException("Quantity must be positive")
        if (holding.quantity - holding.reservedQuantity < quantity) throw ConflictException("Insufficient free allowances on holding $holdingId")
        val updated = holding.copy(reservedQuantity = holding.reservedQuantity + quantity, version = holding.version + 1)
        if (!repository.update(updated)) throw ConflictException("Holding $holdingId changed concurrently")
        return updated
    }

    override fun release(holdingId: UUID, quantity: Long): AllowanceHoldingView {
        val holding = repository.findById(holdingId) ?: throw NotFoundException("AllowanceHolding", holdingId)
        if (quantity <= 0 || quantity > holding.reservedQuantity) throw ValidationException("Cannot release $quantity of ${holding.reservedQuantity} reserved")
        val updated = holding.copy(reservedQuantity = holding.reservedQuantity - quantity, version = holding.version + 1)
        if (!repository.update(updated)) throw ConflictException("Holding $holdingId changed concurrently")
        return updated
    }

    @Transactional(readOnly = true)
    override fun holdings(holderEntityId: UUID): List<AllowanceHoldingView> = repository.byEntity(holderEntityId)

    @Transactional(readOnly = true)
    override fun holding(registryAccountId: UUID, productCode: String): AllowanceHoldingView? = repository.find(registryAccountId, productCode.uppercase())

    override fun holdingById(holdingId: UUID): AllowanceHoldingView? = repository.findById(holdingId)

    @Transactional(readOnly = true)
    override fun movements(holdingId: UUID): List<AllowanceMovementView> = repository.movements(holdingId)

    @Transactional(readOnly = true)
    override fun movementTotal(holderEntityId: UUID, type: AllowanceMovementType, complianceYear: Int, productCodes: Set<String>?): Long =
        repository.movementTotal(holderEntityId, type, complianceYear, productCodes?.map { it.uppercase() }?.toSet())
}
