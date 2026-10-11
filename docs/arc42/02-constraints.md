# 2. Architecture constraints

| Constraint | Background |
|---|---|
| Kotlin on the JVM (JDK 25 LTS), Spring Boot 4, PostgreSQL | Team and ecosystem decision; mature tooling for enterprise energy trading |
| Single deployable (modular monolith) in increment 1 | One team, strong consistency between trade, inventory and ledger |
| No JPA; jOOQ with Flyway-migrated schemas | Explicit SQL, type-safe criteria compilation (ADR 0002) |
| One schema per module, no cross-schema foreign keys | Modules reference each other by id only, so each can be extracted with its data |
| EECS rules (AIB), RED II art. 19 for GoO; EU ETS Directive and Union Registry rules for allowances | Regulatory baseline; registry code lists must be reconciled before production |
| OIDC single sign-on; header-based actor only in `dev`/`test` profiles | Enterprise identity provider (Keycloak locally, any OIDC in production) |
| GL is an external system | The sub-ledger exports journals; it does not replace the general ledger |
