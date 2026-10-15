package com.pianonurmi.etrm.trade.domain

import com.pianonurmi.etrm.platform.paging.Cursor
import java.time.LocalDate

/** Keyset position in the blotter (trade date descending, then trade number descending), opaque to clients. */
data class TradeCursor(val tradeDate: LocalDate, val tradeNumber: String) {
    fun encode(): String = Cursor.encode(tradeDate, tradeNumber)

    companion object {
        fun decode(cursor: String): TradeCursor = Cursor.decode(cursor, 2) { (date, number) -> TradeCursor(LocalDate.parse(date), number) }
    }
}
