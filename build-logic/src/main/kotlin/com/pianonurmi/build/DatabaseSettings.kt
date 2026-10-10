package com.pianonurmi.build

/**
 * Database coordinates used at build time (jOOQ code generation against a Flyway-migrated schema).
 * Defaults match `ops/docker-compose.yml`; override through environment variables in CI or locally.
 */
object DatabaseSettings {
    val codegenUrl: String = System.getenv("ETRM_CODEGEN_DB_URL") ?: "jdbc:postgresql://localhost:5432/etrm_codegen"
    val user: String = System.getenv("ETRM_DB_USER") ?: "etrm"
    val password: String = System.getenv("ETRM_DB_PASSWORD") ?: "etrm"

    fun schemaFor(module: String): String = "etrm_$module"
}
