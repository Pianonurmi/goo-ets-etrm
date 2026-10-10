# ADR 0002: jOOQ with Flyway-migrated schemas instead of JPA

- Status: accepted
- Date: 2026-10-10

## Context
The inventory and allocation paths depend on PostgreSQL features (ltree, range types, exclusion constraints, GiST/GIN
indexes, advisory locks, `FOR UPDATE SKIP LOCKED`) and on compiling the criteria language into SQL. Object-relational
mapping hides exactly the SQL that matters here.

## Decision
Schemas are defined by Flyway migrations per module. jOOQ sources are generated at build time against a migrated
code-generation database (`ETRM_CODEGEN_DB_URL`). Repositories are explicit; aggregates are immutable Kotlin data
classes with optimistic version checks.

## Consequences
- The build needs a PostgreSQL instance (CI service container, docker-compose locally). This is the price of real types.
- No lazy loading surprises; every query is visible and testable.
- PostgreSQL-specific types come through `jooq-postgres-extensions` (`Ltree`, `LocalDateRange`, `BigDecimalRange`).
