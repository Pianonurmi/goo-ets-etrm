package com.pianonurmi.etrm.inventory.application

import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.CancellationDetails
import com.pianonurmi.etrm.inventory.api.HoldingStamp
import com.pianonurmi.etrm.inventory.api.LotCancelled
import com.pianonurmi.etrm.inventory.api.LotCommand
import com.pianonurmi.etrm.inventory.api.LotCorrected
import com.pianonurmi.etrm.inventory.api.LotCorrection
import com.pianonurmi.etrm.inventory.api.LotEventView
import com.pianonurmi.etrm.inventory.api.LotExpired
import com.pianonurmi.etrm.inventory.api.LotFilter
import com.pianonurmi.etrm.inventory.api.LotQuery
import com.pianonurmi.etrm.inventory.api.LotReceived
import com.pianonurmi.etrm.inventory.api.LotReleased
import com.pianonurmi.etrm.inventory.api.LotReserved
import com.pianonurmi.etrm.inventory.api.LotSnapshot
import com.pianonurmi.etrm.inventory.api.LotSplit
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotTransferFailed
import com.pianonurmi.etrm.inventory.api.LotTransferStarted
import com.pianonurmi.etrm.inventory.api.LotTransferred
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.inventory.api.LotWithdrawn
import com.pianonurmi.etrm.inventory.api.ReceiveLots
import com.pianonurmi.etrm.inventory.api.SplitResult
import com.pianonurmi.etrm.inventory.domain.CertificateLot
import com.pianonurmi.etrm.inventory.domain.LotChange
import com.pianonurmi.etrm.inventory.domain.LotEventJournal
import com.pianonurmi.etrm.inventory.domain.LotRepository
import com.pianonurmi.etrm.inventory.domain.NewLotEvent
import com.pianonurmi.etrm.platform.errors.NotFoundException
import com.pianonurmi.etrm.platform.errors.StaleVersionException
import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.json.Json
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.CriteriaService
import com.pianonurmi.etrm.referencedata.api.ReferenceDataQuery
import com.pianonurmi.etrm.referencedata.api.RegistryType
import com.pianonurmi.etrm.taxonomy.api.TaxonomyQuery
import com.pianonurmi.etrm.taxonomy.api.TaxonomyScheme
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
@Transactional
class LotService(
    private val lots: LotRepository,
    private val journal: LotEventJournal,
    private val referenceData: ReferenceDataQuery,
    private val taxonomy: TaxonomyQuery,
    private val criteria: CriteriaService,
    private val actorProvider: ActorProvider,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) : LotCommand, LotQuery {
    // ----------------------------------------------------------------------------------------------------------
    // Commands
    // ----------------------------------------------------------------------------------------------------------

    override fun receive(command: ReceiveLots): LotView {
        val account = referenceData.requireRegistryAccount(command.registryAccountId)
        val registry = referenceData.requireRegistry(account.registryCode)
        if (registry.registryType != RegistryType.GOO) throw ValidationException("Account ${account.accountNumber} is not a GoO account")
        if (account.holderId != command.holderEntityId) {
            throw ValidationException(
                "Account ${account.accountNumber} is not held by entity ${command.holderEntityId}",
            )
        }

        val device = referenceData.productionDeviceByGsrn(command.deviceGsrn)
        val attrs = command.deviceAttributes
        if (device == null && attrs == null) throw ValidationException("Production device ${command.deviceGsrn} is unknown; supply deviceAttributes")
        val energySourceCode = device?.energySourceCode ?: attrs!!.energySourceCode
        val technologyCode = device?.technologyCode ?: attrs?.technologyCode
        val energySource = taxonomy.require(TaxonomyScheme.ENERGY_SOURCE, energySourceCode)
        val technology = technologyCode?.let { taxonomy.require(TaxonomyScheme.TECHNOLOGY, it) }
        command.labels.forEach { taxonomy.require(TaxonomyScheme.LABEL, it) }
        command.supportSchemeCode?.let { taxonomy.require(TaxonomyScheme.SUPPORT_SCHEME, it) }
        val expiry = command.expiryDate ?: command.productionPeriod.endExclusive.plusMonths(12)
        if (expiry < command.productionPeriod.endExclusive) throw ValidationException("Expiry $expiry precedes the production period end")
        if (command.productionPeriod.endExclusive > command.issueDate.plusDays(1) && command.acquisitionSource == AcquisitionSource.ISSUANCE) {
            throw ValidationException("Certificates cannot be issued before the production period ends")
        }

        val id = Ids.newId()
        val lot = LotView(
            id = id,
            holderEntityId = command.holderEntityId,
            registryAccountId = account.id,
            registryCode = account.registryCode,
            issuanceRef = command.issuanceRef.trim(),
            range = command.range,
            quantityMwh = command.range.quantity,
            energyCarrier = command.energyCarrier,
            certificateScheme = command.certificateScheme,
            issuingBody = command.issuingBody.trim(),
            issuingCountry = command.issuingCountry.trim().uppercase(),
            deviceId = device?.id,
            deviceGsrn = command.deviceGsrn.trim(),
            deviceName = device?.name ?: attrs?.name,
            deviceCountry = (device?.country ?: attrs!!.country).uppercase(),
            deviceRegion = device?.regionCode ?: attrs?.region,
            deviceCommissioningDate = device?.commissioningDate ?: attrs?.commissioningDate,
            deviceCapacityKw = device?.capacityKw ?: attrs?.capacityKw,
            energySourceCode = energySource.code,
            energySourcePath = energySource.path,
            technologyCode = technology?.code,
            technologyPath = technology?.path,
            productionPeriod = command.productionPeriod,
            issueDate = command.issueDate,
            expiryDate = expiry,
            supportProduction = command.supportProduction,
            supportInvestment = command.supportInvestment,
            supportSchemeCode = command.supportSchemeCode,
            labels = command.labels,
            status = LotStatus.HELD,
            rootLotId = id,
            parentLotId = null,
            acquisitionSource = command.acquisitionSource,
            acquisitionRef = command.acquisitionRef,
            acquisitionDate = command.acquisitionDate,
            costBasisPerMwh = command.costBasisPerMwh,
            costCurrency = command.costCurrency?.uppercase(),
            reservationRef = null,
            instructionRef = null,
            attributes = command.attributes,
            cancellation = null,
            version = 1,
        )
        val effectiveAt = command.effectiveAt ?: clock.instant()
        val seq = record(lot.id, "LotReceived", effectiveAt, mapOf("lot" to lot, "source" to command.acquisitionSource))
        lots.insert(lot, LotChange(seq, actorProvider.current().userId, effectiveAt))
        events.publishEvent(LotReceived(lot))
        return lot
    }

    override fun requireLotOperator(holderEntityId: UUID) {
        val actor = actorProvider.current()
        actor.requireRole(Role.BACK_OFFICE, Role.REGISTRY_OPERATOR)
        actor.requireEntity(holderEntityId)
    }

    override fun split(lotId: UUID, quantity: Long): SplitResult {
        val aggregate = CertificateLot(locked(lotId))
        val (carved, retained) = aggregate.split(quantity)
        val now = clock.instant()
        val seq = record(lotId, "LotSplit", now, mapOf("carved" to carved.id, "retained" to retained.id, "quantity" to quantity))
        persist(aggregate.state, seq)
        val actor = actorProvider.current().userId
        lots.insert(carved, LotChange(record(carved.id, "LotReceived", now, mapOf("lot" to carved, "source" to "SPLIT", "parent" to lotId)), actor, now))
        lots.insert(retained, LotChange(record(retained.id, "LotReceived", now, mapOf("lot" to retained, "source" to "SPLIT", "parent" to lotId)), actor, now))
        events.publishEvent(LotSplit(aggregate.state, listOf(carved, retained)))
        return SplitResult(retained = retained, carved = carved)
    }

    override fun reserve(lotId: UUID, quantity: Long?, reservationRef: UUID): LotView {
        var target = locked(lotId)
        if (quantity != null && quantity < target.quantityMwh) {
            target = split(lotId, quantity).carved
        } else if (quantity != null && quantity > target.quantityMwh) {
            throw ValidationException("Cannot reserve $quantity from lot of ${target.quantityMwh} MWh")
        }
        val aggregate = CertificateLot(target)
        val next = aggregate.reserve(reservationRef)
        persist(next, record(next.id, "LotReserved", clock.instant(), mapOf("reservationRef" to reservationRef)))
        events.publishEvent(LotReserved(next, reservationRef))
        return next
    }

    override fun release(lotId: UUID, reservationRef: UUID): LotView {
        val next = CertificateLot(locked(lotId)).release(reservationRef)
        persist(next, record(lotId, "LotReleased", clock.instant(), mapOf("reservationRef" to reservationRef)))
        events.publishEvent(LotReleased(next, reservationRef))
        return next
    }

    override fun startTransfer(lotId: UUID, instructionRef: UUID): LotView {
        val next = CertificateLot(locked(lotId)).startTransfer(instructionRef)
        persist(next, record(lotId, "LotTransferStarted", clock.instant(), mapOf("instructionRef" to instructionRef)))
        events.publishEvent(LotTransferStarted(next, instructionRef))
        return next
    }

    override fun confirmTransferOut(lotId: UUID, instructionRef: UUID, externalRef: String?): LotView {
        val next = CertificateLot(locked(lotId)).confirmTransferOut(instructionRef)
        persist(next, record(lotId, "LotTransferred", clock.instant(), mapOf("instructionRef" to instructionRef, "externalRef" to externalRef)))
        events.publishEvent(LotTransferred(next, instructionRef, externalRef))
        return next
    }

    override fun failTransfer(lotId: UUID, instructionRef: UUID, reason: String): LotView {
        val next = CertificateLot(locked(lotId)).failTransfer(instructionRef)
        persist(next, record(lotId, "LotTransferFailed", clock.instant(), mapOf("instructionRef" to instructionRef, "reason" to reason)))
        events.publishEvent(LotTransferFailed(next, instructionRef, reason))
        return next
    }

    override fun cancel(lotId: UUID, quantity: Long?, details: CancellationDetails): LotView {
        var target = locked(lotId)
        if (quantity != null && quantity < target.quantityMwh) target = split(lotId, quantity).carved
        if (quantity != null && quantity > target.quantityMwh) throw ValidationException("Cannot cancel $quantity from lot of ${target.quantityMwh} MWh")
        val next = CertificateLot(target).cancel(details.copy(consumptionCountry = details.consumptionCountry.uppercase()))
        persist(next, record(next.id, "LotCancelled", clock.instant(), mapOf("cancellation" to next.cancellation)))
        events.publishEvent(LotCancelled(next, next.cancellation!!))
        return next
    }

    override fun withdraw(lotId: UUID, reason: String): LotView {
        val next = CertificateLot(locked(lotId)).withdraw()
        persist(next, record(lotId, "LotWithdrawn", clock.instant(), mapOf("reason" to reason)))
        events.publishEvent(LotWithdrawn(next, reason))
        return next
    }

    override fun correct(lotId: UUID, correction: LotCorrection): LotView {
        val current = locked(lotId)
        correction.labels?.forEach { taxonomy.require(TaxonomyScheme.LABEL, it) }
        val corrected = current.copy(
            labels = correction.labels ?: current.labels,
            supportProduction = correction.supportProduction ?: current.supportProduction,
            supportInvestment = correction.supportInvestment ?: current.supportInvestment,
            supportSchemeCode = correction.supportSchemeCode ?: current.supportSchemeCode,
            expiryDate = correction.expiryDate ?: current.expiryDate,
            costBasisPerMwh = correction.costBasisPerMwh ?: current.costBasisPerMwh,
            costCurrency = correction.costCurrency ?: current.costCurrency,
        )
        val changed = buildSet {
            if (corrected.labels != current.labels) add("labels")
            if (corrected.supportProduction != current.supportProduction) add("supportProduction")
            if (corrected.supportInvestment != current.supportInvestment) add("supportInvestment")
            if (corrected.supportSchemeCode != current.supportSchemeCode) add("supportSchemeCode")
            if (corrected.expiryDate != current.expiryDate) add("expiryDate")
            if (corrected.costBasisPerMwh != current.costBasisPerMwh || corrected.costCurrency != current.costCurrency) add("costBasis")
        }
        if (changed.isEmpty()) return current
        val next = CertificateLot(current).correct(corrected)
        val effectiveAt = correction.effectiveAt ?: clock.instant()
        persist(next, record(lotId, "LotCorrected", effectiveAt, mapOf("changed" to changed, "reason" to correction.reason)), effectiveAt)
        events.publishEvent(LotCorrected(next, changed))
        return next
    }

    override fun expireDueLots(asOf: LocalDate): Int {
        var count = 0
        lots.dueForExpiry(asOf).forEach { id ->
            val next = CertificateLot(locked(id)).expire(asOf)
            persist(next, record(id, "LotExpired", clock.instant(), mapOf("asOf" to asOf)))
            events.publishEvent(LotExpired(next))
            count++
        }
        return count
    }

    // ----------------------------------------------------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    override fun lot(id: UUID): LotView? = lots.find(id)

    @Transactional(readOnly = true)
    override fun requireLot(id: UUID): LotView = lots.find(id) ?: throw NotFoundException("Lot", id)

    @Transactional(readOnly = true)
    override fun lots(filter: LotFilter): List<LotView> = lots.list(filter)

    @Transactional(readOnly = true)
    override fun visibleLots(filter: LotFilter): List<LotView> {
        val actor = actorProvider.current()
        filter.holderEntityId?.let { actor.requireEntity(it) }
        return lots.list(filter.copy(holderEntityIds = actor.entityFilter))
    }

    @Transactional(readOnly = true)
    override fun requireVisibleLot(id: UUID): LotView = requireLot(id).also { actorProvider.current().requireEntity(it.holderEntityId) }

    @Transactional(readOnly = true)
    override fun search(holderEntityId: UUID, bound: BoundCriterion, statuses: Set<LotStatus>, limit: Int): List<LotView> {
        actorProvider.current().requireEntity(holderEntityId)
        return lots.search(holderEntityId, criteria.compile(bound, LotCriteriaColumns), statuses, limit)
    }

    @Transactional(readOnly = true)
    override fun history(lotId: UUID): List<LotEventView> = journal.history(lotId)

    @Transactional(readOnly = true)
    override fun snapshot(holderEntityId: UUID): LotSnapshot = LotSnapshot(holderEntityId, journal.lastSeq(), lots.live(holderEntityId))

    @Transactional(readOnly = true)
    override fun journalSeq(): Long = journal.lastSeq()

    @Transactional(readOnly = true)
    override fun holdingStamp(holderEntityId: UUID): HoldingStamp = lots.holdingStamp(holderEntityId)

    @Transactional(readOnly = true)
    override fun verifyJournal(): List<UUID> = journal.brokenChains()

    @Transactional(readOnly = true)
    override fun asOf(lotId: UUID, effectiveAt: Instant, knownAt: Instant): Map<String, Any?>? = lots.asOf(lotId, effectiveAt, knownAt)

    @Transactional(readOnly = true)
    override fun expiryLadder(holderEntityId: UUID, from: LocalDate, months: Int): Map<LocalDate, Long> =
        lots.expiryLadder(holderEntityId, from.withDayOfMonth(1), from.withDayOfMonth(1).plusMonths(months.toLong()))

    // ----------------------------------------------------------------------------------------------------------

    private fun locked(id: UUID): LotView = lots.lock(id) ?: throw NotFoundException("Lot", id)

    private fun record(lotId: UUID, type: String, effectiveAt: Instant, payload: Map<String, Any?>): Long {
        val actor = actorProvider.current()
        val normalised: Map<String, Any?> = Json.mapper.readValue(Json.mapper.writeValueAsString(payload), object : TypeReference<Map<String, Any?>>() {})
        return journal.append(NewLotEvent(lotId, type, effectiveAt, actor.userId, null, normalised))
    }

    private fun persist(lot: LotView, seq: Long, effectiveAt: Instant = clock.instant()) {
        if (!lots.update(lot, LotChange(seq, actorProvider.current().userId, effectiveAt))) {
            throw StaleVersionException("Lot", lot.id, lot.version - 1, -1)
        }
    }
}
