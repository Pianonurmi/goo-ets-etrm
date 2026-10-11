package com.pianonurmi.etrm

import com.pianonurmi.etrm.platform.test.TestDatabase
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * A database next to the shared test database (`<name>_<suffix>` on the same server), emptied of ETRM schemas, for
 * full-context tests whose data would collide with other tests (e.g. the demo seeder's market data).
 */
object SiblingDatabase {
    fun register(registry: DynamicPropertyRegistry, suffix: String) {
        val (siblingUrl, user, password) = prepare(suffix)
        registry.add("spring.datasource.url") { siblingUrl }
        registry.add("spring.datasource.username") { user }
        registry.add("spring.datasource.password") { password }
    }

    /** A plain data source on the emptied sibling database, for tests without a Spring context. */
    fun dataSource(suffix: String): DataSource {
        val (url, user, password) = prepare(suffix)
        return DriverManagerDataSource(url, user, password)
    }

    private fun prepare(suffix: String): Triple<String, String, String> {
        val properties = TestDatabase.springProperties()
        val url = properties.getValue("spring.datasource.url")
        val user = properties.getValue("spring.datasource.username")
        val password = properties.getValue("spring.datasource.password")
        val base = url.substringBefore('?')
        val name = base.substringAfterLast('/') + "_" + suffix
        val siblingUrl = base.substringBeforeLast('/') + "/" + name + url.removePrefix(base)
        DriverManager.getConnection(url, user, password).use { connection ->
            val exists = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?").use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { it.next() }
            }
            if (!exists) connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        DriverManager.getConnection(siblingUrl, user, password).use { connection ->
            val schemas = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT nspname FROM pg_namespace WHERE nspname LIKE 'etrm\\_%'").use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
            connection.createStatement().use { statement -> schemas.forEach { statement.execute("DROP SCHEMA \"$it\" CASCADE") } }
        }
        return Triple(siblingUrl, user, password)
    }
}
