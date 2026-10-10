package com.pianonurmi.etrm.trade.domain

import java.time.LocalDate
import java.util.Base64

/** Keyset position in the blotter (trade date descending, then trade number descending), opaque to clients. */
data class TradeCursor(val tradeDate: LocalDate, val tradeNumber: String) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$tradeDate|$tradeNumber".toByteArray())

    companion object {
        fun decode(cursor: String): TradeCursor {
            val text = runCatching { String(Base64.getUrlDecoder().decode(cursor)) }.getOrNull()
            val (date, number) = text?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: throw IllegalArgumentException("Invalid cursor")
            return TradeCursor(runCatching { LocalDate.parse(date) }.getOrElse { throw IllegalArgumentException("Invalid cursor") }, number)
        }
    }
}
