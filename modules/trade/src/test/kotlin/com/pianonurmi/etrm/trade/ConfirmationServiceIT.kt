package com.pianonurmi.etrm.trade

import com.pianonurmi.etrm.document.adapter.jooq.JooqDocumentRepository
import com.pianonurmi.etrm.document.adapter.pdf.OpenHtmlPdfRenderer
import com.pianonurmi.etrm.document.application.DocumentService
import com.pianonurmi.etrm.platform.errors.SegregationOfDutiesException
import com.pianonurmi.etrm.platform.money.QuantityUnit
import com.pianonurmi.etrm.platform.security.Actor
import com.pianonurmi.etrm.platform.security.ActorProvider
import com.pianonurmi.etrm.platform.security.Role
import com.pianonurmi.etrm.platform.test.TestDatabase
import com.pianonurmi.etrm.platform.time.LocalDateRange
import com.pianonurmi.etrm.product.adapter.jooq.JooqAllowanceProductRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqCriteriaRepository
import com.pianonurmi.etrm.product.adapter.jooq.JooqProductBucketRepository
import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.product.api.criteria.Criterion
import com.pianonurmi.etrm.product.application.CriteriaServiceImpl
import com.pianonurmi.etrm.product.application.ProductBucketSeeder
import com.pianonurmi.etrm.product.application.ProductBucketService
import com.pianonurmi.etrm.product.application.ProductCatalogueService
import com.pianonurmi.etrm.product.application.TaxonomySnapshots
import com.pianonurmi.etrm.referencedata.adapter.jooq.JooqReferenceDataRepository
import com.pianonurmi.etrm.referencedata.api.BookType
import com.pianonurmi.etrm.referencedata.api.CreateBook
import com.pianonurmi.etrm.referencedata.api.CreateCounterparty
import com.pianonurmi.etrm.referencedata.api.CreateLegalEntity
import com.pianonurmi.etrm.referencedata.application.ReferenceDataService
import com.pianonurmi.etrm.taxonomy.adapter.jooq.JooqTaxonomyRepository
import com.pianonurmi.etrm.taxonomy.application.TaxonomyService
import com.pianonurmi.etrm.trade.adapter.jooq.JooqConfirmationRepository
import com.pianonurmi.etrm.trade.adapter.jooq.JooqTradeRepository
import com.pianonurmi.etrm.trade.api.AmendTrade
import com.pianonurmi.etrm.trade.api.CaptureDelivery
import com.pianonurmi.etrm.trade.api.CaptureLeg
import com.pianonurmi.etrm.trade.api.CaptureTrade
import com.pianonurmi.etrm.trade.api.ConfirmationDirection
import com.pianonurmi.etrm.trade.api.ConfirmationMismatched
import com.pianonurmi.etrm.trade.api.ConfirmationSource
import com.pianonurmi.etrm.trade.api.ConfirmationStatus
import com.pianonurmi.etrm.trade.api.ConfirmationTerms
import com.pianonurmi.etrm.trade.api.DifferenceSeverity
import com.pianonurmi.etrm.trade.api.PartyTerms
import com.pianonurmi.etrm.trade.api.ReceiveConfirmation
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeAmended
import com.pianonurmi.etrm.trade.api.TradeApproved
import com.pianonurmi.etrm.trade.api.TradeCaptured
import com.pianonurmi.etrm.trade.api.TradeStatus
import com.pianonurmi.etrm.trade.application.ConfirmationService
import com.pianonurmi.etrm.trade.application.TradeService
import com.pianonurmi.etrm.trade.domain.ConfirmationMatcher
import com.pianonurmi.etrm.trade.domain.ConfirmationTolerance
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Our confirmation is issued on approval; the counterparty's is matched field by field. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfirmationServiceIT {
    private val published = mutableListOf<Any>()
    private var currentUser = "trader1"
    private val actor = ActorProvider { Actor(currentUser, currentUser, setOf(Role.TRADER, Role.MIDDLE_OFFICE, Role.BACK_OFFICE)) }
    private lateinit var confirmations: ConfirmationService
    private val bus = ApplicationEventPublisher { event ->
        published += event
        when (event) {
            is TradeApproved -> confirmations.on(event)
            is TradeCaptured -> confirmations.on(event)
            is TradeAmended -> confirmations.on(event)
        }
    }
    private lateinit var trades: TradeService
    private lateinit var documents: DocumentService
    private lateinit var entity: UUID
    private lateinit var book: UUID
    private lateinit var counterparty: UUID

    @BeforeAll
    fun setUp() {
        TestDatabase.migrate("taxonomy", "referencedata", "product", "trade", "document")
        val dsl = TestDatabase.dsl
        val taxonomy = TaxonomyService(JooqTaxonomyRepository(dsl), bus)
        val referenceData = ReferenceDataService(JooqReferenceDataRepository(dsl), taxonomy, bus)
        val criteria = CriteriaServiceImpl(JooqCriteriaRepository(dsl), TaxonomySnapshots(taxonomy))
        val buckets = ProductBucketService(JooqProductBucketRepository(dsl), criteria)
        ProductBucketSeeder(buckets, buckets).seed()
        val clock = Clock.systemUTC()
        trades = TradeService(
            JooqTradeRepository(dsl),
            referenceData,
            criteria,
            buckets,
            ProductCatalogueService(JooqAllowanceProductRepository(dsl)),
            actor,
            bus,
            clock,
            BigDecimal.ZERO,
        )
        documents = DocumentService(JooqDocumentRepository(dsl), OpenHtmlPdfRenderer(), actor, bus, clock)
        confirmations = ConfirmationService(JooqConfirmationRepository(dsl), trades, referenceData, documents, actor, bus, clock)
        entity = referenceData.createLegalEntity(
            CreateLegalEntity("CONF-CO", "Confirming Oy", "FI", lei = "743700AAAAAAAAAAAA24", validFrom = LocalDate.of(2026, 1, 1)),
        ).id
        book = referenceData.createBook(CreateBook(entity, "CONF-B", "Confirmations", BookType.TRADING)).id
        counterparty = referenceData.createCounterparty(CreateCounterparty("CONF-CP", "Buyer GmbH", "DE", lei = "529900BBBBBBBBBBBB91")).id
    }

    private fun approvedSale(price: String = "1.85", externalRef: String? = null): UUID {
        currentUser = "trader1"
        val trade = trades.capture(
            CaptureTrade(
                legalEntityId = entity,
                bookId = book,
                counterpartyId = counterparty,
                productFamily = ProductFamily.GUARANTEE_OF_ORIGIN,
                instrumentType = InstrumentType.FORWARD,
                tradeDate = LocalDate.of(2026, 10, 1),
                externalRef = externalRef,
                legs = listOf(
                    CaptureLeg(
                        side = Side.SELL,
                        criterion = Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO"), Criterion.productionYear(2026)),
                        quantity = BigDecimal(1_000),
                        unit = QuantityUnit.MWH,
                        price = BigDecimal(price),
                        counterpartyAccountRef = "HKNR:DE-1",
                        deliveries = listOf(CaptureDelivery(LocalDateRange.ofQuarter(2026, 4), LocalDate.of(2026, 12, 15), BigDecimal(1_000))),
                    ),
                ),
            ),
        )
        currentUser = "middle-office"
        trades.approve(trade.id)
        return trade.id
    }

    private fun ours(tradeId: UUID) = confirmations.forTrade(tradeId).single {
        it.direction == ConfirmationDirection.OURS && it.status == ConfirmationStatus.ISSUED
    }

    /** The counterparty's view of the same trade: our terms, their reference, possibly a different price. */
    private fun theirTerms(tradeId: UUID, price: BigDecimal? = null, quoteOurRef: Boolean = true): ConfirmationTerms {
        val terms = ours(tradeId).terms
        return terms.copy(
            tradeRef = if (quoteOurRef) terms.tradeRef else null,
            legs = terms.legs.map { leg ->
                val p = price ?: leg.price
                leg.copy(
                    price = p,
                    amount = (leg.quantity * p).setScale(2, java.math.RoundingMode.HALF_EVEN),
                    attributes = leg.attributes.mapKeys {
                        it.key.lowercase()
                    },
                )
            },
        )
    }

    @Test
    fun `approval issues our confirmation with a PDF, and a matching confirmation confirms the trade as system`() {
        val tradeId = approvedSale()
        val ours = ours(tradeId)
        ours.documentId shouldNotBe null
        documents.content(ours.documentId!!).bytes.take(4) shouldBe "%PDF".toByteArray().toList()
        ours.terms.legs.single().let {
            it.seller.lei shouldBe "743700AAAAAAAAAAAA24"
            it.buyer.lei shouldBe "529900BBBBBBBBBBBB91"
            it.attributes shouldBe mapOf("REGION" to "NORDIC", "ENERGY_SOURCE" to "HYDRO", "VINTAGE" to "2026")
            it.amount.compareTo(BigDecimal("1850.00")) shouldBe 0
        }
        confirmations.issue(tradeId).id shouldBe ours.id

        currentUser = "backoffice"
        // Within the price tolerance (0.0001 EUR/MWh) the confirmation matches.
        val theirs = confirmations.receive(ReceiveConfirmation(theirTerms(tradeId, BigDecimal("1.850004")), "CP-1"))
        theirs.status shouldBe ConfirmationStatus.MATCHED
        theirs.matchedAgainst shouldBe ours.id
        trades.requireTrade(tradeId).let {
            it.status shouldBe TradeStatus.CONFIRMED
            it.confirmationRef shouldBe "CP-1"
        }
        trades.versions(tradeId).last().recordedBy shouldBe "system"
        confirmations.receive(ReceiveConfirmation(theirTerms(tradeId), "CP-1")).id shouldBe theirs.id
    }

    @Test
    fun `a mismatch waits for a second person, who may override it`() {
        val tradeId = approvedSale()
        currentUser = "backoffice"
        val theirs = confirmations.receive(ReceiveConfirmation(theirTerms(tradeId, BigDecimal("1.90")), "CP-2"))
        theirs.status shouldBe ConfirmationStatus.MISMATCHED
        theirs.differences.filter { it.severity == DifferenceSeverity.MISMATCH }.map { it.field }.toSet() shouldBe
            setOf("legs[1].price", "legs[1].amount")
        published.filterIsInstance<ConfirmationMismatched>().single { it.confirmation.id == theirs.id }
        trades.requireTrade(tradeId).status shouldBe TradeStatus.APPROVED

        shouldThrow<SegregationOfDutiesException> { confirmations.override(theirs.id, "backoffice", "fine") }
        confirmations.override(theirs.id, "middle-office", "price agreed by phone").status shouldBe ConfirmationStatus.OVERRIDDEN
        trades.requireTrade(tradeId).let {
            it.status shouldBe TradeStatus.CONFIRMED
            it.confirmationRef shouldBe "CP-2"
        }
    }

    @Test
    fun `a disputed confirmation leaves the trade unconfirmed, and fixing our trade re-matches theirs`() {
        val tradeId = approvedSale(externalRef = "THEIR-77")
        currentUser = "backoffice"
        // Found by the counterparty's own trade id when ours is not quoted.
        val wrongPrice = confirmations.receive(ReceiveConfirmation(theirTerms(tradeId, BigDecimal("1.95"), quoteOurRef = false), "CP-3", counterparty))
        wrongPrice.status shouldBe ConfirmationStatus.MISMATCHED
        confirmations.dispute(wrongPrice.id, "middle-office", "we agreed 1.85").status shouldBe ConfirmationStatus.DISPUTED
        trades.requireTrade(tradeId).status shouldBe TradeStatus.APPROVED

        // They were right after all: a corrected confirmation arrives, then we amend and re-approve the trade.
        val corrected = confirmations.receive(ReceiveConfirmation(theirTerms(tradeId, BigDecimal("1.95")), "CP-3b"))
        corrected.status shouldBe ConfirmationStatus.MISMATCHED
        currentUser = "trader1"
        val trade = trades.requireTrade(tradeId)
        trades.amend(
            tradeId,
            AmendTrade(
                expectedVersion = trade.version,
                reason = "price per counterparty",
                legs = listOf(
                    CaptureLeg(
                        side = Side.SELL,
                        criterion = Criterion.all(Criterion.region("NORDIC"), Criterion.energySource("HYDRO"), Criterion.productionYear(2026)),
                        quantity = BigDecimal(1_000),
                        unit = QuantityUnit.MWH,
                        price = BigDecimal("1.95"),
                        counterpartyAccountRef = "HKNR:DE-1",
                        deliveries = listOf(CaptureDelivery(LocalDateRange.ofQuarter(2026, 4), LocalDate.of(2026, 12, 15), BigDecimal(1_000))),
                    ),
                ),
            ),
        )
        confirmations.forTrade(tradeId).filter { it.direction == ConfirmationDirection.OURS && it.status == ConfirmationStatus.ISSUED }.shouldBeEmpty()
        confirmations.confirmation(corrected.id)!!.status shouldBe ConfirmationStatus.RECEIVED
        currentUser = "middle-office"
        trades.approve(tradeId)
        confirmations.confirmation(corrected.id)!!.status shouldBe ConfirmationStatus.MATCHED
        trades.requireTrade(tradeId).status shouldBe TradeStatus.CONFIRMED
        confirmations.forTrade(tradeId).filter { it.direction == ConfirmationDirection.OURS } shouldHaveSize 2
    }

    @Test
    fun `counterparty confirmations arrive as CSV, one line per delivery`() {
        val tradeId = approvedSale()
        val number = trades.requireTrade(tradeId).tradeNumber
        currentUser = "backoffice"
        val csv = """
            confirmation_ref,our_trade_ref,trade_date,product_family,seller_lei,buyer_lei,product,quantity,unit,price,currency,delivery_date,buyer_account,attr_region,attr_energy_source,attr_vintage
            CSV-1,$number,2026-10-01,GUARANTEE_OF_ORIGIN,743700AAAAAAAAAAAA24,529900BBBBBBBBBBBB91,ELECTRICITY,1000,MWh,1.85,EUR,2026-12-15,HKNR:DE-1,Nordic,Hydro,2026
        """.trimIndent()
        confirmations.receiveCsv(counterparty, csv).single().let {
            it.source shouldBe ConfirmationSource.CSV
            it.status shouldBe ConfirmationStatus.MATCHED
        }
    }

    @Test
    fun `the matcher notes what the counterparty leaves out and refuses what differs`() {
        val tradeId = approvedSale()
        val ours = ours(tradeId).terms
        val theirs = ours.copy(
            legs = ours.legs.map {
                it.copy(seller = PartyTerms("Seller", null), attributes = it.attributes - "VINTAGE", sellerAccount = "HKNR:XX")
            },
        )
        val differences = ConfirmationMatcher.compare(ours, theirs, ConfirmationTolerance())
        differences.single { it.field == "legs[1].seller.lei" }.severity shouldBe DifferenceSeverity.NOTE
        differences.single { it.field == "legs[1].attributes.VINTAGE" }.severity shouldBe DifferenceSeverity.MISMATCH
        ConfirmationMatcher.matches(differences) shouldBe false
        ConfirmationMatcher.matches(ConfirmationMatcher.compare(ours, ours, ConfirmationTolerance())) shouldBe true
    }
}
