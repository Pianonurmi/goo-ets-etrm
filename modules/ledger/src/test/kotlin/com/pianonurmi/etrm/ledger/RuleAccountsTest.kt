package com.pianonurmi.etrm.ledger

import com.pianonurmi.etrm.ledger.api.PostingRuleView
import com.pianonurmi.etrm.ledger.api.PostingSide
import com.pianonurmi.etrm.ledger.api.RuleLine
import com.pianonurmi.etrm.ledger.domain.RuleAccounts
import com.pianonurmi.etrm.platform.errors.ValidationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Period end reads the accounts its own posting rules book to (ADR 0008), so a remapped rule moves the measurement. */
class RuleAccountsTest {
    private val entity = UUID.randomUUID()
    private val day = LocalDate.of(2026, 9, 30)

    private fun rule(
        code: String,
        event: String,
        vararg lines: RuleLine,
        conditions: Map<String, String> = emptyMap(),
        legalEntityId: UUID? = null,
    ) = PostingRuleView(UUID.randomUUID(), legalEntityId, code, event, conditions, lines.toList(), null, 100, LocalDate.of(2020, 1, 1), true, 1)

    private val nrv = listOf(
        rule(
            "NRV_GOO",
            "PeriodEndNrv",
            RuleLine(PostingSide.DEBIT, "4310", "increase"),
            RuleLine(PostingSide.CREDIT, "1490", "increase"),
            conditions = mapOf("asset" to "GOO"),
        ),
        rule("NRV_ALLOWANCE", "PeriodEndNrv", RuleLine(PostingSide.CREDIT, "1491", "increase"), conditions = mapOf("asset" to "ALLOWANCE")),
    )

    @Test
    fun `the account comes from the rule line that posts the amount, narrowed by the attributes`() {
        RuleAccounts.account(nrv, entity, "PeriodEndNrv", day, PostingSide.CREDIT, "increase", mapOf("asset" to "GOO")) shouldBe "1490"
        RuleAccounts.account(nrv, entity, "PeriodEndNrv", day, PostingSide.CREDIT, "increase", mapOf("asset" to "ALLOWANCE")) shouldBe "1491"
        RuleAccounts.account(nrv, entity, "PeriodEndNrv", day, PostingSide.DEBIT, "increase", mapOf("asset" to "GOO")) shouldBe "4310"
    }

    @Test
    fun `an entity's own rule overrides the default, and an ambiguous or missing account is refused`() {
        val remapped = nrv + rule(
            "NRV_GOO",
            "PeriodEndNrv",
            RuleLine(PostingSide.CREDIT, "1495", "increase"),
            conditions = mapOf("asset" to "GOO"),
            legalEntityId = entity,
        )
        RuleAccounts.account(remapped, entity, "PeriodEndNrv", day, PostingSide.CREDIT, "increase", mapOf("asset" to "GOO")) shouldBe "1495"
        RuleAccounts.account(remapped, UUID.randomUUID(), "PeriodEndNrv", day, PostingSide.CREDIT, "increase", mapOf("asset" to "GOO")) shouldBe "1490"

        shouldThrow<ValidationException> { RuleAccounts.account(nrv, entity, "PeriodEndNrv", day, PostingSide.CREDIT, "increase") }
            .message shouldContain "several accounts (1490, 1491)"
        shouldThrow<ValidationException> { RuleAccounts.account(nrv, entity, "PeriodEndMtm", day, PostingSide.DEBIT, "assetIncrease") }
            .message shouldContain "No posting rule"
    }

    @Test
    fun `condition values list what the rules apply to`() {
        val fx = listOf(
            rule("FX_PAYABLE", "PeriodEndFxRevaluation", RuleLine(PostingSide.DEBIT, "2210", "gain"), conditions = mapOf("account" to "2210")),
            rule("FX_RECEIVABLE", "PeriodEndFxRevaluation", RuleLine(PostingSide.DEBIT, "1510", "gain"), conditions = mapOf("account" to "1510")),
        )
        RuleAccounts.conditionValues(fx, entity, "PeriodEndFxRevaluation", day, "account") shouldBe listOf("1510", "2210")
    }
}
