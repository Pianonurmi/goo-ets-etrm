package com.pianonurmi.etrm.platform.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CsvTest {
    @Test
    fun `fields with delimiters, quotes and line breaks round-trip`() {
        val rows = listOf(listOf("plain", "a,b", "say \"hi\"", "two\nlines", null, 42))
        val text = Csv.write(listOf("a", "b", "c", "d", "e", "f"), rows)
        text shouldBe "a,b,c,d,e,f\r\nplain,\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\",,42\r\n"
        Csv.parse(text) shouldBe listOf(listOf("a", "b", "c", "d", "e", "f"), listOf("plain", "a,b", "say \"hi\"", "two\nlines", "", "42"))
    }

    @Test
    fun `parse skips blank lines and supports other delimiters`() {
        Csv.parse("x;y\n\n1;\"2;3\"\n") shouldBe listOf(listOf("x;y"), listOf("1;\"2;3\""))
        Csv.parse("x;y\n\n1;\"2;3\"\n", ';') shouldBe listOf(listOf("x", "y"), listOf("1", "2;3"))
    }
}
