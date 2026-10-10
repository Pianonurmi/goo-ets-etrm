package com.pianonurmi.etrm.reporting

import com.pianonurmi.etrm.referencedata.api.EmirClassification
import com.pianonurmi.etrm.reporting.api.EmirAction
import com.pianonurmi.etrm.reporting.api.EmirEventView
import com.pianonurmi.etrm.reporting.api.EmirFieldRule
import com.pianonurmi.etrm.reporting.domain.EmirActions
import com.pianonurmi.etrm.reporting.domain.EmirCompletenessCheck
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class EmirRulesTest {
    @Test
    fun `a confirmation is new the first time and a modification later, nothing after a termination`() {
        EmirActions.onConfirmation(emptyList()) shouldBe EmirAction.NEWT
        EmirActions.onConfirmation(listOf(EmirAction.NEWT, EmirAction.VALU)) shouldBe EmirAction.MODI
        EmirActions.onConfirmation(listOf(EmirAction.NEWT, EmirAction.TERM)) shouldBe null
        EmirActions.onConfirmation(listOf(EmirAction.VALU)) shouldBe EmirAction.NEWT
    }

    @Test
    fun `only a reported trade is terminated when cancelled, and only once`() {
        EmirActions.onCancellation(emptyList()) shouldBe null
        EmirActions.onCancellation(listOf(EmirAction.NEWT, EmirAction.MODI)) shouldBe EmirAction.TERM
        EmirActions.onCancellation(listOf(EmirAction.NEWT, EmirAction.TERM)) shouldBe null
        EmirActions.onCancellation(listOf(EmirAction.NEWT, EmirAction.EROR)) shouldBe null
    }

    @Test
    fun `valuations are reported for financial and NFC+ counterparties only`() {
        EmirActions.reportsValuations(EmirClassification.FC) shouldBe true
        EmirActions.reportsValuations(EmirClassification.NFC_PLUS) shouldBe true
        EmirActions.reportsValuations(EmirClassification.NFC_MINUS) shouldBe false
        EmirActions.reportsValuations(null) shouldBe false
    }

    @Test
    fun `completeness checks the fields that apply to the action and to cleared trades`() {
        val rules = listOf(
            EmirFieldRule("UTI", "ALL", "", false),
            EmirFieldRule("UPI", "TRADE", "", false),
            EmirFieldRule("CCP", "CLEARED", "", false),
            EmirFieldRule("VALUATION_AMOUNT", "VALUATION", "", false),
        )

        fun event(action: EmirAction, fields: Map<String, String?>) =
            EmirEventView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "T-1", 1, null, action, LocalDate.EPOCH, "s", fields, "u", Instant.EPOCH)
        EmirCompletenessCheck.missing(event(EmirAction.NEWT, mapOf("UTI" to "X", "UPI" to null, "CLEARED" to "N")), rules) shouldBe listOf("UPI")
        EmirCompletenessCheck.missing(event(EmirAction.NEWT, mapOf("UTI" to "X", "UPI" to "U", "CLEARED" to "Y")), rules) shouldBe listOf("CCP")
        EmirCompletenessCheck.missing(event(EmirAction.VALU, mapOf("UTI" to "")), rules) shouldBe listOf("UTI", "VALUATION_AMOUNT")
    }
}
