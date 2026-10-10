package com.pianonurmi.etrm.referencedata.domain

import com.pianonurmi.etrm.platform.errors.ValidationException
import com.pianonurmi.etrm.platform.errors.Violation
import java.time.ZoneId
import java.util.Currency

/** Validation rules for master data that go beyond database constraints. */
object ReferenceDataPolicies {
    private val codePattern = Regex("^[A-Z0-9_-]{2,30}$")
    private val countryPattern = Regex("^[A-Z]{2}$")
    private val leiPattern = Regex("^[A-Z0-9]{18}[0-9]{2}$")
    private val gsrnPattern = Regex("^[0-9]{18}$")

    fun code(value: String, field: String = "code"): String {
        val normalised = value.trim().uppercase()
        if (!codePattern.matches(normalised)) throw violation(field, "must match ${codePattern.pattern}")
        return normalised
    }

    fun country(value: String): String {
        val normalised = value.trim().uppercase()
        if (!countryPattern.matches(normalised)) throw violation("country", "must be ISO 3166-1 alpha-2")
        return normalised
    }

    fun lei(value: String?): String? {
        if (value == null) return null
        val normalised = value.trim().uppercase()
        if (!leiPattern.matches(normalised)) throw violation("lei", "must be a 20 character ISO 17442 LEI")
        if (!leiChecksumValid(normalised)) throw violation("lei", "checksum invalid")
        return normalised
    }

    fun currency(value: String): Currency = runCatching { Currency.getInstance(value.trim().uppercase()) }
        .getOrElse { throw violation("currency", "unknown ISO 4217 code '$value'") }

    fun timeZone(value: String): String = runCatching { ZoneId.of(value).id }
        .getOrElse { throw violation("timeZone", "unknown time zone '$value'") }

    /** GS1 GSRN: 18 digits with a mod-10 check digit. */
    fun gsrn(value: String): String {
        val normalised = value.trim()
        if (!gsrnPattern.matches(normalised)) throw violation("gsrn", "must be 18 digits")
        if (!gs1CheckDigitValid(normalised)) throw violation("gsrn", "GS1 check digit invalid")
        return normalised
    }

    fun gs1CheckDigitValid(digits: String): Boolean {
        val body = digits.dropLast(1)
        val sum = body.reversed().mapIndexed { index, c -> (c - '0') * if (index % 2 == 0) 3 else 1 }.sum()
        val check = (10 - sum % 10) % 10
        return check == digits.last() - '0'
    }

    /** ISO 7064 mod 97-10 as used by LEI. */
    private fun leiChecksumValid(lei: String): Boolean {
        val numeric = StringBuilder()
        lei.forEach { c -> numeric.append(if (c.isDigit()) c.toString() else (c - 'A' + 10).toString()) }
        var remainder = 0
        numeric.forEach { d -> remainder = (remainder * 10 + (d - '0')) % 97 }
        return remainder == 1
    }

    private fun violation(field: String, message: String) = ValidationException("$field $message", listOf(Violation(field, message)))
}
