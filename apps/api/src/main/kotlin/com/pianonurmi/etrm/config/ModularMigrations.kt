package com.pianonurmi.etrm.config

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import javax.sql.DataSource

/**
 * Runs Flyway per module schema in dependency order. Each module owns `db/migration/<module>` and schema
 * `etrm_<module>`; there are no foreign keys across module schemas (modules reference each other by id only),
 * so each history table is independent and a module can be extracted with its schema intact.
 */
@Configuration
@EnableConfigurationProperties(MigrationProperties::class)
class ModularMigrationsConfiguration {
    @Bean
    fun modularMigrations(dataSource: DataSource, properties: MigrationProperties): ModularMigrations =
        ModularMigrations(dataSource, properties)

    companion object {
        /**
         * Make every database-touching bean wait for migrations, the same way Spring Boot's own Flyway
         * auto-configuration does for `JdbcTemplate` and `DSLContext`.
         */
        @Bean
        @JvmStatic
        fun migrationsDependsOnPostProcessor(): BeanFactoryPostProcessor = BeanFactoryPostProcessor { factory ->
            dependsOnMigrations(factory, "org.jooq.DSLContext")
            dependsOnMigrations(factory, "org.springframework.jdbc.core.JdbcOperations")
            dependsOnMigrations(factory, "org.springframework.jdbc.core.simple.JdbcClient")
            dependsOnMigrations(factory, "org.springframework.modulith.events.core.EventPublicationRepository")
        }

        private fun dependsOnMigrations(factory: ConfigurableListableBeanFactory, typeName: String) {
            val type = runCatching { Class.forName(typeName) }.getOrNull() ?: return
            factory.getBeanNamesForType(type, true, false).forEach { name ->
                val definition: BeanDefinition = factory.getBeanDefinition(name)
                definition.setDependsOn(*((definition.dependsOn ?: emptyArray()) + "modularMigrations"))
            }
        }
    }
}

@ConfigurationProperties(prefix = "etrm.migrations")
data class MigrationProperties(
    val enabled: Boolean = true,
    /** Module order; a module only migrates when its migration folder is present on the classpath. */
    val modules: List<String> = DEFAULT_ORDER,
) {
    companion object {
        val DEFAULT_ORDER = listOf(
            "platform",
            "document",
            "referencedata",
            "taxonomy",
            "product",
            "marketdata",
            "trade",
            "inventory",
            "allocation",
            "registry",
            "etscompliance",
            "position",
            "settlement",
            "valuation",
            "ledger",
            "workflow",
            "audit",
        )
    }
}

class ModularMigrations(private val dataSource: DataSource, private val properties: MigrationProperties) : InitializingBean {
    private val log = LoggerFactory.getLogger(ModularMigrations::class.java)

    override fun afterPropertiesSet() {
        if (!properties.enabled) {
            log.warn("Database migrations are disabled (etrm.migrations.enabled=false)")
            return
        }
        val resolver = PathMatchingResourcePatternResolver()
        properties.modules.forEach { module ->
            val location = "classpath:db/migration/$module"
            val present = resolver.getResources("$location/*.sql").isNotEmpty()
            if (!present) {
                log.debug("No migrations for module {}; skipping", module)
                return@forEach
            }
            val result = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schemaFor(module))
                .defaultSchema(schemaFor(module))
                .createSchemas(true)
                .table("flyway_history")
                .locations(location)
                .validateMigrationNaming(true)
                .load()
                .migrate()
            log.info("Module {}: schema {} at version {} ({} applied)", module, schemaFor(module), result.targetSchemaVersion, result.migrationsExecuted)
        }
    }

    companion object {
        fun schemaFor(module: String): String = "etrm_$module"
    }
}
