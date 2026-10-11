package com.pianonurmi.etrm.config

import javax.sql.DataSource

/** Runs before the module migrations; the showcase wipes the database here (ADR 0033). */
fun interface MigrationHook {
    fun beforeMigrations(dataSource: DataSource)
}
