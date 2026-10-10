package com.pianonurmi.etrm.trade.domain

import java.math.BigInteger

/** Format checks of the identifiers regulatory reports carry. */
object RegulatoryChecks {
    private val MIC = Regex("^[A-Z0-9]{4}$")
    private val UPI = Regex("^[A-Z0-9]{12}$")
    private val UTI = Regex("^[A-Z0-9]{21,52}$")
    private val ISIN = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")
    private val LEI = Regex("^[A-Z0-9]{18}[0-9]{2}$")

    fun mic(value: String): Boolean = MIC.matches(value)

    fun upi(value: String): Boolean = UPI.matches(value)

    /** ISO 17442: 20 characters, check digits by ISO 7064 mod 97-10. */
    fun lei(value: String): Boolean = LEI.matches(value) && BigInteger(digits(value)).mod(BigInteger.valueOf(97)) == BigInteger.ONE

    /** ISO 23897: the generating entity's LEI and up to 32 further characters. */
    fun uti(value: String): Boolean = UTI.matches(value) && lei(value.take(20))

    /** ISO 6166: country prefix, nine characters and a Luhn check digit over the letters expanded to numbers. */
    fun isin(value: String): Boolean {
        if (!ISIN.matches(value)) return false
        val expanded = digits(value.dropLast(1))
        val sum = expanded.reversed().mapIndexed { i, c ->
            val d = c - '0'
            if (i % 2 == 0) (d * 2).let { if (it > 9) it - 9 else it } else d
        }.sum()
        return (10 - sum % 10) % 10 == value.last() - '0'
    }

    /** Letters as two-digit numbers (A = 10 … Z = 35), digits as themselves. */
    private fun digits(value: String): String = value.map { if (it.isDigit()) it.toString() else (it - 'A' + 10).toString() }.joinToString("")
}
