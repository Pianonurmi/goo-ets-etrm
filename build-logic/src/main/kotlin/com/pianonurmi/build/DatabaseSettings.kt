package com.pianonurmi.build

import org.gradle.api.provider.ProviderFactory
import java.io.Serializable

/**
 * Database coordinates used at build time (jOOQ code generation against a Flyway-migrated schema). Defaults match
 * `ops/docker-compose.yml`; override through environment variables in CI or locally.
 *
 * Read per build through [ProviderFactory], never cached in a static: a warm daemon keeps build-logic classes loaded,
 * so a static would keep the first URL it saw, and the configuration cache tracks provider reads but not
 * `System.getenv`. Each worktree can then point `ETRM_CODEGEN_DB_URL` at its own database.
 */
data class DatabaseSettings(val codegenUrl: String, val user: String, val password: String) : Serializable {
    companion object {
        private const val DEFAULT_URL = "jdbc:postgresql://localhost:5432/etrm_codegen"

        fun from(providers: ProviderFactory) = DatabaseSettings(
            codegenUrl = providers.environmentVariable("ETRM_CODEGEN_DB_URL").getOrElse(DEFAULT_URL),
            user = providers.environmentVariable("ETRM_DB_USER").getOrElse("etrm"),
            password = providers.environmentVariable("ETRM_DB_PASSWORD").getOrElse("etrm"),
        )

        fun schemaFor(module: String): String = "etrm_$module"
    }
}
