# Penetration test preparation

This page is for the testing firm and the people who host the test. It sets out what to test, how to get
access, what is out of bounds, and what we have already checked ourselves.

## Scope

| In scope | Notes |
|---|---|
| Web UI and REST API (`/`, `/api/v1/**`) of the test environment | One image; the OpenAPI document of the build under test is `apps/api/openapi/etrm-api.json` |
| Authentication with the identity provider's tokens (OIDC, bearer JWT) | Token handling only, not the identity provider itself |
| Authorisation | Roles, legal-entity scope, four-eyes rules, segregation of duties |
| File uploads | Payment, statement, transaction and confirmation CSV; documents are generated, never uploaded |
| Business logic | Approvals, allocation, deliveries, invoices, netting, reconciliation, ledger postings |

| Out of scope | Why |
|---|---|
| The identity provider, Kubernetes cluster, PostgreSQL, Kafka | Platform tests by their owners |
| Registries (Fingrid, Union Registry, other national registries) | Third-party systems: the test environment uses the simulator, and no test may send instructions to a real registry |
| Denial of service and load | Load is covered by `docs/operations/load-testing.md` |
| The management port (9090) | Reachable only inside the cluster (network policy); it is in scope only for a test run from inside the cluster |

## Environment and accounts

- A dedicated environment: the Helm chart with `environment=PENTEST`, its own database seeded with the demo data,
  registry gateways on the simulator, and Kafka off.
- One test account per role, plus a second user for each approving role so that four-eyes paths can be tested.
  The development realm in `ops/keycloak/etrm-realm.json` shows the shape. In the test realm the accounts are:

| Account | Roles | Entity scope |
|---|---|---|
| `pt-trader` | TRADER | all |
| `pt-middle`, `pt-middle2` | MIDDLE_OFFICE | all |
| `pt-backoffice`, `pt-backoffice2` | BACK_OFFICE | all |
| `pt-registry` | REGISTRY_OPERATOR | all |
| `pt-accountant`, `pt-accountant2` | ACCOUNTANT | all |
| `pt-risk` | RISK | all |
| `pt-auditor` | AUDITOR | all |
| `pt-admin` | ADMIN | all |
| `pt-scoped` | TRADER, MIDDLE_OFFICE | one legal entity only (`legal_entities` claim with its id; the others have `*`) |

## What we would like tested first

1. **Horizontal access across legal entities:** the `pt-scoped` user reading or changing another entity's trades,
   lots, invoices, documents, breaks or ledger entries. That includes identifiers taken from another session and
   list endpoints without filters.
2. **Four eyes:** approving one's own request (trades, allocation plans, invoices, netting, VAT rules, curve
   snapshots, valuation runs, limits, accounting policies, registry instructions, master-data change requests),
   also by changing the request in between or by acting through a bulk decision.
3. **Mass assignment and state:** fields in request bodies that the API should ignore (status, approver,
   amounts already set). Actions on records in a state that forbids them.
4. **CSV imports:** formula injection on export (CSV opened in a spreadsheet), malformed and very large files,
   encodings.
5. **Append-only records:** any way to change or delete audit events, lot and ledger journal entries or issued
   documents without the hash chains showing it.
6. **Tokens:** signature, issuer and audience checks, expired tokens, tokens with the `none` algorithm, and roles
   injected through other claims.

## Already checked by us

| Control | Where |
|---|---|
| Role and entity checks on every command and read | `OperationGuardsIT` (every command, no roles), `*GuardsIT`, `EntityScopeIT`, `SecuredChainIT` |
| Token validation: signature, expiry, issuer, audience, unsigned tokens | `TokenValidationIT` (Spring Boot's decoder against a stub key set) |
| Encoded paths cannot bypass authentication | `EncodedPathIT` (every OpenAPI path, plain and percent-encoded, secured and showcase chains) |
| Security headers: CSP without inline scripts, `frame-ancestors 'none'`, referrer policy, no framing | `SecurityConfiguration`, Playwright `hardening.spec.ts` (axe and CSP) |
| Dependency and image vulnerabilities | Trivy gate on every build, weekly OWASP dependency check, SBOMs (`docs/operations/supply-chain.md`) |
| Passive scan of the running application | ZAP baseline (`.github/workflows/zap.yml`, weekly), rules and accepted findings in `.zap/rules.tsv`. Last run on 2026-10-11 against the secured profile: 55 rules passed, no failures, one warning kept on purpose (CSP `style-src 'unsafe-inline'`, required by Mantine). |
| OpenAPI document and Swagger UI | Behind authentication outside `dev` unless `etrm.security.public-api-docs=true` |
| Rate of failed logins, password policy, MFA | The identity provider |

## Rules of engagement

- **Window:** agreed dates, working hours in Europe/Helsinki. The contact is on call for stopping the test.
- **Findings:** critical findings are reported at once, by phone and in writing, without waiting for the
  report.
- **Data:** demo data only, and no real personal data. Testers do not keep any data after the test.
- **Report:** each finding with reproduction steps, the request and response, CVSS 3.1 or 4.0, and affected
  endpoints. Findings are tracked as issues with the `security` label and retested after the fix.
