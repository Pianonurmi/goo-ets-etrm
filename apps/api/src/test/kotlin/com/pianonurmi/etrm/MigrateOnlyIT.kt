package com.pianonurmi.etrm

import com.pianonurmi.etrm.config.MigrateOnly
import com.pianonurmi.etrm.platform.test.TestDatabase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** `--migrate-only` (the Helm pre-upgrade Job) migrates every module and exits with 0, and with 1 when it cannot. */
class MigrateOnlyIT {
    private fun args(overrides: Map<String, String> = emptyMap()) =
        (TestDatabase.springProperties() + overrides).map { (key, value) -> "--$key=$value" }.toTypedArray() + MigrateOnly.FLAG

    @Test
    fun `migrates every module schema and exits with 0`() {
        MigrateOnly.requested(args()) shouldBe true
        MigrateOnly.run(args()) shouldBe 0
        TestDatabase.dataSource.connection.use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM etrm_ledger.flyway_history WHERE success").use { rs ->
                rs.next()
                (rs.getInt(1) > 0) shouldBe true
            }
        }
    }

    @Test
    fun `exits with 1 when the database cannot be reached`() {
        MigrateOnly.run(args(mapOf("spring.datasource.url" to "jdbc:postgresql://127.0.0.1:1/none", "spring.datasource.hikari.connection-timeout" to "1000"))) shouldBe 1
    }
}
