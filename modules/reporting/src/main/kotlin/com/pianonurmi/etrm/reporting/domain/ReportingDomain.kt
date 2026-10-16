package com.pianonurmi.etrm.reporting.domain

import com.pianonurmi.etrm.product.api.InstrumentType
import com.pianonurmi.etrm.referencedata.api.EmirClassification
import com.pianonurmi.etrm.reporting.api.EmirAction
import com.pianonurmi.etrm.reporting.api.EmirEventView
import com.pianonurmi.etrm.reporting.api.EmirFieldRule
import com.pianonurmi.etrm.reporting.api.ReportExportView
import com.pianonurmi.etrm.reporting.api.ReportResult
import com.pianonurmi.etrm.trade.api.ClearingStatus
import com.pianonurmi.etrm.trade.api.OptionType
import com.pianonurmi.etrm.trade.api.Side
import com.pianonurmi.etrm.trade.api.TradeView
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The parties of a trade as EMIR needs them. */
data class EmirParties(
    val reportingLei: String?,
    val classification: EmirClassification?,
    val otherCounterpartyLei: String?,
    val intragroup: Boolean,
    val ccpLei: String?,
)

/**
 * Maps a trade to EMIR report fields. The names follow the EMIR Refit report but are not verified against the
 * regulatory technical standards (ADR 0023).
 */
object EmirFields {
    private val contractTypes = mapOf(
        InstrumentType.FORWARD to "FORW",
        InstrumentType.FUTURE to "FUTR",
        InstrumentType.SWAP to "SWAP",
        InstrumentType.OPTION to "OPTN",
    )

    fun nature(classification: EmirClassification?): String? = when (classification) {
        EmirClassification.FC -> "F"
        EmirClassification.NFC_PLUS, EmirClassification.NFC_MINUS -> "N"
        EmirClassification.THIRD_COUNTRY_ENTITY, EmirClassification.OTHER, null -> null
    }

    private fun common(trade: TradeView, parties: EmirParties, action: EmirAction, eventDate: LocalDate): Map<String, String?> = mapOf(
        "UTI" to trade.regulatory.uti,
        "REPORTING_COUNTERPARTY" to parties.reportingLei,
        "OTHER_COUNTERPARTY" to parties.otherCounterpartyLei,
        "NATURE_OF_REPORTING_COUNTERPARTY" to nature(parties.classification),
        "ACTION_TYPE" to action.name,
        "EVENT_DATE" to eventDate.toString(),
    )

    /** NEWT, MODI, TERM and EROR carry the trade's terms. */
    fun trade(trade: TradeView, parties: EmirParties, action: EmirAction, eventDate: LocalDate): Map<String, String?> {
        val leg = trade.legs.first()
        // An option delivers nothing; it matures at its expiry (ADR 0047).
        val deliveries = trade.legs.flatMap { it.deliveries }.map { it.deliveryDate } + trade.legs.mapNotNull { it.option?.expiryDate }
        val option = leg.option
        val cleared = trade.regulatory.clearingStatus == ClearingStatus.CLEARED
        return common(trade, parties, action, eventDate) + mapOf(
            "COUNTERPARTY_SIDE" to if (leg.side == Side.BUY) "BUYR" else "SLLR",
            "ASSET_CLASS" to "COMM",
            "CONTRACT_TYPE" to contractTypes[trade.instrumentType],
            "UPI" to leg.upi,
            "ISIN" to leg.isin,
            "VENUE" to trade.regulatory.venueMic,
            "EXECUTION_TIMESTAMP" to trade.regulatory.executionTimestamp?.toString(),
            "EFFECTIVE_DATE" to deliveries.minOrNull()?.toString(),
            "EXPIRATION_DATE" to deliveries.maxOrNull()?.toString(),
            "DELIVERY_TYPE" to "PHYS",
            "PRICE" to leg.price.toPlainString(),
            "PRICE_CURRENCY" to leg.priceCurrency.currencyCode,
            "NOTIONAL" to trade.notional.toPlainString(),
            "NOTIONAL_CURRENCY" to leg.priceCurrency.currencyCode,
            "TOTAL_QUANTITY" to trade.legs.sumOf { it.quantity }.toPlainString(),
            "QUANTITY_UNIT" to leg.unit.name,
            "CLEARED" to if (cleared) "Y" else "N",
            "CCP" to parties.ccpLei.takeIf { cleared },
            "INTRAGROUP" to if (parties.intragroup) "Y" else "N",
        ) + (
            option?.let {
                mapOf(
                    "OPTION_TYPE" to if (it.optionType == OptionType.CALL) "CALL" else "PUTO",
                    "OPTION_EXERCISE_STYLE" to "EURO",
                    "STRIKE_PRICE" to it.strike.toPlainString(),
                    "STRIKE_PRICE_CURRENCY" to leg.priceCurrency.currencyCode,
                    "MATURITY_DATE_OF_UNDERLYING" to null,
                    "UNDERLYING" to it.underlyingContractCode,
                )
            } ?: emptyMap()
        )
    }

    fun valuation(trade: TradeView, parties: EmirParties, eventDate: LocalDate, amount: BigDecimal, currency: String, at: Instant): Map<String, String?> =
        common(trade, parties, EmirAction.VALU, eventDate) + mapOf(
            "VALUATION_AMOUNT" to amount.toPlainString(),
            "VALUATION_CURRENCY" to currency,
            "VALUATION_TIMESTAMP" to at.toString(),
            "VALUATION_METHOD" to "MTMA",
        )
}

/** Which lifecycle action a trade event becomes, given what was recorded for the trade before. */
object EmirActions {
    private val lifecycle = setOf(EmirAction.NEWT, EmirAction.MODI, EmirAction.TERM, EmirAction.EROR)

    /** A confirmation is new the first time, a modification after that; nothing after a termination or error. */
    fun onConfirmation(previous: List<EmirAction>): EmirAction? = when {
        previous.any { it == EmirAction.TERM || it == EmirAction.EROR } -> null
        previous.any { it in lifecycle } -> EmirAction.MODI
        else -> EmirAction.NEWT
    }

    /** A cancelled trade that was reported is terminated; one never reported needs nothing. */
    fun onCancellation(previous: List<EmirAction>): EmirAction? = when {
        previous.none { it == EmirAction.NEWT } -> null
        previous.any { it == EmirAction.TERM || it == EmirAction.EROR } -> null
        else -> EmirAction.TERM
    }

    /** Only valuations of FC and NFC+ counterparties are reported. */
    fun reportsValuations(classification: EmirClassification?): Boolean =
        classification == EmirClassification.FC || classification == EmirClassification.NFC_PLUS
}

object EmirCompletenessCheck {
    fun missing(event: EmirEventView, rules: List<EmirFieldRule>): List<String> {
        val cleared = event.fields["CLEARED"] == "Y"
        return rules.filter { rule ->
            when (rule.appliesTo) {
                "ALL" -> true
                "TRADE" -> event.action != EmirAction.VALU
                "VALUATION" -> event.action == EmirAction.VALU
                "CLEARED" -> event.action != EmirAction.VALU && cleared
                else -> false
            }
        }.map { it.fieldName }.filter { event.fields[it].isNullOrBlank() }.sorted()
    }
}

/** MiFID II ancillary activity: how a trade counts in the yearly emission allowance figures. */
enum class AncillaryCategory { TRADING, HEDGING, COMPLIANCE, INTRAGROUP }

interface ReportingRepository {
    fun nextExportSeq(reportCode: String, legalEntityId: UUID?): Int

    fun insertExport(export: ReportExportView, content: String)

    fun exports(legalEntityId: UUID?, reportCode: String?): List<ReportExportView>

    /** The export and its content; the content is null once it expired. */
    fun export(id: UUID): Pair<ReportExportView, String?>?

    /** Drops the content of up to [limit] exports produced before [before]; returns how many. */
    fun expireContent(before: Instant, at: Instant, limit: Int): Int

    /** Records an EMIR event once per trade, action and source; returns whether it was recorded now. */
    fun insertEmirEvent(event: EmirEventView): Boolean

    fun emirEvents(tradeId: UUID): List<EmirEventView>

    fun emirEvents(legalEntityId: UUID, from: LocalDate?, toExclusive: LocalDate?): List<EmirEventView>

    fun emirFieldRules(): List<EmirFieldRule>
}

/** A report: who may run it, its parameters, and how its rows are made. */
interface ReportDefinition {
    val code: String
    val title: String
    val description: String
    val roles: Set<com.pianonurmi.etrm.platform.security.Role>
    val parameters: List<com.pianonurmi.etrm.reporting.api.ReportParameter>

    /** Whether the report belongs to one legal entity (parameter `legalEntityId`). */
    val perEntity: Boolean get() = parameters.any { it.name == "legalEntityId" }

    fun run(parameters: ReportParameters): ReportResult
}

/** Typed access to a report's string parameters. */
class ReportParameters(val values: Map<String, String>) {
    fun uuid(name: String): UUID = values[name]?.let(UUID::fromString) ?: throw missing(name)

    fun date(name: String, default: LocalDate? = null): LocalDate = values[name]?.let(LocalDate::parse) ?: default ?: throw missing(name)

    fun int(name: String, default: Int? = null): Int = values[name]?.toInt() ?: default ?: throw missing(name)

    fun string(name: String): String = values[name] ?: throw missing(name)

    private fun missing(name: String) = com.pianonurmi.etrm.platform.errors.ValidationException("Parameter $name is required")
}
