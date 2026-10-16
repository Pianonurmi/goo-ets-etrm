package com.pianonurmi.etrm

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * A module's migrations change only the module's own schema (increment 16 M3): each module owns `etrm_<module>` and
 * no foreign key or statement crosses schemas, so one module's migration cannot break another module, and a module
 * can be migrated, restored or split out on its own. A migration in `db/migration/<module>/` that names
 * `etrm_<other>.` fails the build. Comments are ignored.
 */
class MigrationSchemaTest {
    private val migrations = PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*/*.sql")
        .associate { resource ->
            val url = resource.url.toString()
            val module = url.substringBeforeLast('/').substringAfterLast('/')
            "$module/${resource.filename}" to resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private val violations = migrations.flatMap { (path, sql) ->
        val module = path.substringBefore('/')
        SCHEMA.findAll(withoutComments(sql)).map { it.groupValues[1] }.filter { it != module }.distinct()
            .map { path to "$path names etrm_$it" }
            .toList()
    }

    @Test
    fun `migrations name no schema but their module's own`() {
        migrations.size shouldBeGreaterThan MIN_MIGRATIONS
        violations.filterNot { it.first in EXCEPTIONS }.map { it.second }.shouldBeEmpty()
    }

    @Test
    fun `the historical exceptions are still caught by the scan`() {
        // Each exception must still be a violation the scan finds: a stale entry could excuse a new migration, and an
        // exception the scan misses means the scan is broken.
        violations.map { it.first }.toSet() shouldContainAll EXCEPTIONS
    }

    private fun withoutComments(sql: String): String =
        sql.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").lines().joinToString("\n") { it.substringBefore("--") }

    private companion object {
        /** `etrm_x.` or `"etrm_x".`: a qualified name in another module's schema. */
        val SCHEMA = Regex("\"?\\betrm_([a-z_]+)\"?\\s*\\.")

        /** Ledger V3 moved the FX rates into market data before the rule existed (increment 3). */
        val EXCEPTIONS = setOf("ledger/V3__fx_rates_to_market_data.sql")

        /** Guards against a classpath that finds no migrations, which would pass vacuously. */
        const val MIN_MIGRATIONS = 40
    }
}
