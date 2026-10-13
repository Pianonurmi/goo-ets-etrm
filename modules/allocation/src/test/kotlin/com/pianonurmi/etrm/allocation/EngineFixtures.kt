package com.pianonurmi.etrm.allocation

import com.pianonurmi.etrm.allocation.api.AllocationConstraint
import com.pianonurmi.etrm.allocation.api.ObligationKind
import com.pianonurmi.etrm.allocation.api.ObligationStatus
import com.pianonurmi.etrm.allocation.api.ObligationView
import com.pianonurmi.etrm.allocation.api.ScoreBreakdown
import com.pianonurmi.etrm.allocation.domain.AllocationProblem
import com.pianonurmi.etrm.allocation.domain.LotClass
import com.pianonurmi.etrm.allocation.domain.LotClassifier
import com.pianonurmi.etrm.allocation.domain.ObligationModel
import com.pianonurmi.etrm.allocation.domain.ProblemShape
import com.pianonurmi.etrm.allocation.domain.Scoring
import com.pianonurmi.etrm.allocation.domain.ValuePolicy
import com.pianonurmi.etrm.inventory.api.AcquisitionSource
import com.pianonurmi.etrm.inventory.api.CertificateRange
import com.pianonurmi.etrm.inventory.api.LotStatus
import com.pianonurmi.etrm.inventory.api.LotView
import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.api.BoundBuckets
import com.pianonurmi.etrm.product.api.EnergyCarrier
import com.pianonurmi.etrm.product.api.ProductBucketView
import com.pianonurmi.etrm.product.api.criteria.BindContext
import com.pianonurmi.etrm.product.api.criteria.BoundCriterion
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.api.criteria.LotAttribute
import com.pianonurmi.etrm.product.domain.CriteriaBinder
import com.pianonurmi.etrm.product.domain.CriteriaEvaluator
import com.pianonurmi.etrm.registry.api.TransferRoute
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.BitSet
import java.util.UUID

/** In-memory fixtures for engine tests: no database, no Spring. */
object EngineFixtures {
    val entity: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val account: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    val paths = mapOf(
        "RENEWABLE" to "RENEWABLE",
        "WIND" to "RENEWABLE.WIND",
        "WIND_ONSHORE" to "RENEWABLE.WIND.WIND_ONSHORE",
        "HYDRO" to "RENEWABLE.HYDRO",
        "HYDRO_RESERVOIR" to "RENEWABLE.HYDRO.HYDRO_RESERVOIR",
        "SOLAR" to "RENEWABLE.SOLAR",
        "NUCLEAR" to "NON_RENEWABLE.NUCLEAR",
    )
    val regions = mapOf("NORDIC" to setOf("FI", "SE", "NO", "DK"), "EEA" to setOf("FI", "SE", "NO", "DK", "DE", "FR", "NL", "ES"))

    fun ctx(delivery: LocalDate) = BindContext(delivery, {
        regions[it] ?: emptySet()
    }, { attr, code -> if (attr == LotAttribute.ENERGY_SOURCE) paths[code] else null })

    fun lot(
        country: String,
        source: String,
        quantity: Long,
        device: String = "DEV-$country-$source",
        month: YearMonth = YearMonth.of(2026, 3),
        labels: Set<String> = emptySet(),
        expiry: LocalDate? = null,
        registry: String = "FINGRID",
        costBasis: BigDecimal? = BigDecimal("1.00"),
    ): LotView {
        val id = Ids.newId()
        val period = LocalDateRange.ofMonth(month)
        return LotView(
            id = id,
            holderEntityId = entity,
            registryAccountId = account,
            registryCode = registry,
            issuanceRef = "ISS-$id",
            range = CertificateRange.of(1, quantity),
            quantityMwh = quantity,
            energyCarrier = EnergyCarrier.ELECTRICITY,
            certificateScheme = "EECS_GO",
            issuingBody = "IB",
            issuingCountry = country,
            deviceId = null,
            deviceGsrn = device,
            deviceName = device,
            deviceCountry = country,
            deviceRegion = null,
            deviceCommissioningDate = LocalDate.of(2015, 1, 1),
            deviceCapacityKw = BigDecimal(10_000),
            energySourceCode = source,
            energySourcePath = paths.getValue(source),
            technologyCode = null,
            technologyPath = null,
            productionPeriod = period,
            issueDate = period.endExclusive.plusDays(10),
            expiryDate = expiry ?: period.endExclusive.plusMonths(12),
            supportProduction = false,
            supportInvestment = false,
            supportSchemeCode = null,
            labels = labels,
            status = LotStatus.HELD,
            rootLotId = id,
            parentLotId = null,
            acquisitionSource = AcquisitionSource.ISSUANCE,
            acquisitionRef = null,
            acquisitionDate = period.endExclusive,
            costBasisPerMwh = costBasis,
            costCurrency = "EUR",
            reservationRef = null,
            instructionRef = null,
            attributes = emptyMap(),
            cancellation = null,
            version = 1,
        )
    }

    fun obligation(
        criterion: Criterion,
        quantity: Long,
        delivery: LocalDate = LocalDate.of(2026, 12, 15),
        priority: Int = 1,
        constraints: List<AllocationConstraint> = emptyList(),
        id: UUID = Ids.newId(),
        bucket: String? = null,
        vintage: Int? = null,
    ) = ObligationView(
        id = id,
        kind = ObligationKind.SALE,
        holderEntityId = entity,
        bookId = null,
        counterpartyId = null,
        tradeRef = null,
        sourceRef = null,
        criterion = criterion,
        criteriaSummary = criterion.toString(),
        quantityMwh = quantity,
        fulfilledMwh = 0,
        deliveryWindow = LocalDateRange.inclusive(delivery.minusDays(30), delivery),
        deliveryDate = delivery,
        targetRegistryCode = "FINGRID",
        targetAccountRef = "CP-1",
        cancellation = null,
        priorityClass = priority,
        constraints = constraints,
        status = ObligationStatus.OPEN,
        version = 1,
        bucketCode = bucket,
        vintageYear = vintage,
    )

    fun bucket(code: String, criterion: Criterion, specificity: Int) = ProductBucketView(
        id = Ids.newId(),
        code = code,
        name = code,
        energyCarrier = EnergyCarrier.ELECTRICITY,
        criteriaId = Ids.newId(),
        criterion = criterion,
        summary = criterion.toString(),
        specificity = BigDecimal(specificity),
        markCurveRef = null,
        sortOrder = specificity,
        active = true,
        version = 1,
    )

    private class Shape(val classes: List<LotClass>, val models: List<ObligationModel>, val eligible: List<BitSet>) : ProblemShape {
        override val obligations: List<ObligationModel> get() = models

        override val quantities: LongArray = LongArray(classes.size) { classes[it].quantity }

        override fun eligibleRow(obl: ObligationModel): BitSet = eligible[obl.index]

        fun eligibleSupply(obl: ObligationModel): Long = eligible[obl.index].stream().mapToLong { classes[it].quantity }.sum()
    }

    private fun shape(lots: List<LotView>, obligations: List<ObligationView>): Shape {
        val classes = LotClassifier.classify(lots, emptyMap())
        val models = obligations.mapIndexed { index, o ->
            val c = ctx(o.deliveryDate)
            val (minShares, maxShare) = AllocationProblem.constraintsOf(o)
            ObligationModel(
                index,
                o,
                CriteriaBinder.bind(o.criterion, c),
                minShares.map {
                    CriteriaBinder.bind(it.criterion, c) to it.percent
                },
                maxShare,
                BigDecimal.ONE,
            )
        }
        // One row per (criterion, delivery date), as ProblemBuilder does: obligations sharing both share eligibility.
        val rows = HashMap<Pair<Criterion, LocalDate>, BitSet>()
        val eligible = models.map { m ->
            rows.getOrPut(m.obligation.criterion to m.obligation.deliveryDate) {
                BitSet().also { bits ->
                    classes.forEach { cls ->
                        if (cls.expiryDate > m.obligation.deliveryDate && CriteriaEvaluator.matches(m.bound, cls.representative)) bits.set(cls.index)
                    }
                }
            }
        }
        return Shape(classes, models, eligible)
    }

    /** Builds a problem with a simple valuation: more specific bucket proxies are not needed, so score = cost basis + scarcity. */
    fun problem(
        lots: List<LotView>,
        obligations: List<ObligationView>,
        values: (LotView) -> BigDecimal = { it.costBasisPerMwh ?: BigDecimal.ZERO },
    ): AllocationProblem {
        val shape = shape(lots, obligations)
        val competition = HashMap<Int, BigDecimal>()
        shape.models.forEach { m ->
            val supply = shape.eligibleSupply(m)
            if (supply > 0) {
                shape.eligible[m.index].stream().forEach { c ->
                    competition.merge(c, BigDecimal(m.demand).divide(BigDecimal(supply), 6, java.math.RoundingMode.HALF_UP), BigDecimal::add)
                }
            }
        }
        return AllocationProblem(
            shape.classes,
            shape.models,
            shape.eligible,
            scoring = { cls: LotClass, _: ObligationModel ->
                val value = values(cls.representative)
                val scarcity = competition[cls.index] ?: BigDecimal.ZERO
                ScoreBreakdown(value, BigDecimal.ZERO, scarcity, BigDecimal.ZERO, (value + scarcity).setScale(6), null)
            },
            // These scores ignore the obligation entirely.
            scoreKey = { Unit },
        )
    }

    /** A problem scored as ProblemBuilder scores it (ADR 0035): bucket marks in EUR, expiry haircut, transfer fee. */
    fun valued(
        lots: List<LotView>,
        obligations: List<ObligationView>,
        buckets: List<ProductBucketView>,
        marks: (String, Int) -> BigDecimal?,
        policy: ValuePolicy = ValuePolicy(),
        feePerMwh: BigDecimal = BigDecimal.ZERO,
    ): AllocationProblem {
        val shape = shape(lots, obligations)
        val bound = BoundBuckets(buckets.map { it to CriteriaBinder.bind(it.criterion, ctx(LocalDate.of(2026, 1, 1))) }, CriteriaEvaluator::matches)
        val scoring = Scoring(bound, marks, { from, to -> TransferRoute(from, to, true, if (from == to) BigDecimal.ZERO else feePerMwh, 0) }, policy)
        val scarcity = scoring.scarcityIndex(shape)
        return AllocationProblem(
            shape.classes,
            shape.models,
            shape.eligible,
            scoring = { cls, obl -> scoring.score(cls, obl, scarcity) },
            scoreKey = { obl -> obl.obligation.deliveryDate to obl.obligation.targetRegistryCode },
        )
    }

    fun evaluate(bound: BoundCriterion, subject: com.pianonurmi.etrm.product.api.criteria.CriteriaSubject): Boolean = CriteriaEvaluator.matches(bound, subject)
}
