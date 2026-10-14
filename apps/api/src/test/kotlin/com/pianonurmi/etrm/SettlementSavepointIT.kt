package com.pianonurmi.etrm

import com.pianonurmi.etrm.platform.ids.Ids
import com.pianonurmi.etrm.product.api.ProductFamily
import com.pianonurmi.etrm.settlement.api.SettlementDirection
import com.pianonurmi.etrm.settlement.api.SettlementItemCommand
import com.pianonurmi.etrm.settlement.api.SettlementItemStatus
import com.pianonurmi.etrm.settlement.api.SettlementItemView
import com.pianonurmi.etrm.settlement.domain.SettlementItemRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Evaluating a settlement item calls other modules' `@Transactional` services, which throw when a counterparty is
 * missing. The item is parked and the surrounding transaction must still commit (regression for D5: the caught
 * `DomainException` marked it rollback-only). Runs with real Spring transaction proxies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SettlementSavepointIT {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) = TestWiring.register(registry)
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var items: SettlementItemCommand

    @Autowired
    private lateinit var repository: SettlementItemRepository

    @Autowired
    private lateinit var transactions: PlatformTransactionManager

    @Test
    fun `an item whose counterparty cannot be found is parked and the transaction that evaluates it commits`() {
        val scenario = ApiScenario(port).referenceData()
        val item = SettlementItemView(
            id = Ids.newId(),
            deliveryId = Ids.newId(),
            tradeId = Ids.newId(),
            tradeNumber = "T-SAVEPOINT",
            scheduleId = Ids.newId(),
            legalEntityId = UUID.fromString(scenario.entityId),
            counterpartyId = Ids.newId(),
            direction = SettlementDirection.RECEIVABLE,
            productFamily = ProductFamily.GUARANTEE_OF_ORIGIN,
            description = "Guarantees of origin",
            quantity = BigDecimal(100),
            unit = "MWH",
            unitPrice = BigDecimal("2.00"),
            currency = "EUR",
            netAmount = null,
            deliveredOn = LocalDate.of(2026, 10, 5),
            vatRuleCode = null,
            vatRuleVersion = null,
            vatTreatment = null,
            vatRate = null,
            vatAmount = null,
            status = SettlementItemStatus.NEEDS_ATTENTION,
            problem = "waiting",
            invoiceId = null,
            reference = null,
            version = 1,
        )
        repository.insert(item)

        // The counterparty lookup throws NotFoundException inside the ReferenceDataService proxy; commit must not fail.
        val parked = TransactionTemplate(transactions).execute { items.reevaluate(item.id) }

        parked.status shouldBe SettlementItemStatus.NEEDS_ATTENTION
        parked.problem!! shouldContain "Counterparty"
        parked.version shouldBe 2
        // The parked state was committed, not rolled back with the failed lookup.
        repository.find(item.id)!!.let {
            it.version shouldBe 2
            it.problem!! shouldContain "Counterparty"
        }
    }
}
