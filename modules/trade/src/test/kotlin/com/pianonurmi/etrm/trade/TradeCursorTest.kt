package com.pianonurmi.etrm.trade

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.trade.domain.TradeCursor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class TradeCursorTest {
    @Test
    fun `round-trips and refuses garbage`() {
        val cursor = TradeCursor(LocalDate.of(2026, 10, 1), "T-2026-000042")
        TradeCursor.decode(cursor.encode()) shouldBe cursor
        shouldThrow<ValidationException> { TradeCursor.decode("not-a-cursor") }
        shouldThrow<ValidationException> { TradeCursor.decode(java.util.Base64.getUrlEncoder().encodeToString("2026-13-01|T".toByteArray())) }
    }
}
