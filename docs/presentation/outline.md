# Provena: presentation outline

Eighteen slides, about 25 minutes: the product on its real screens (1–10), then how it is built for an enterprise that
values reliability, security, robustness and maintainability, and the roadmap (11–18). The deck uses the Provena brand (`docs/ui/design-system.md`). Its
pictures come from `docs/images` (`npm run docs:screenshots`). Speaker notes follow each slide.

The slides are published as a deck on claude.ai, built from this outline and the screenshots; download it as
PowerPoint or PDF from the deck's Share menu: <https://claude.ai/artifact/NHG4trUF1EkTnB6dXmAxyv>. When the screens
change, retake the screenshots and replace the pictures in the deck.

## 1. Provena: certificates and allowances, from trade to ledger

- A trading and risk system for guarantees of origin and EU emission allowances, built for audited, regulated
  operations.
- Picture: `home-dark.png`.

*Notes:* One system that follows a certificate from the trade to the registry, the invoice and the general ledger.
Everything shown is the working system, running on fictitious data.

## 2. Why this is hard

- A guarantee of origin is a range of certificate numbers with attributes: country, source, technology, age,
  support.
- A sale names criteria, not lots; any lot that satisfies them can deliver.
- Registries differ: the Union Registry, national GoO registries, the AIB hub, files and APIs.
- Every step that moves value needs a second person; compliance deadlines are fixed by law.

*Notes:* Spreadsheets break on exactly these points: criteria matching, registry state and four eyes.

## 3. One system, every role

- Trading, operations, finance and risk, and administration in one application, with one inbox for every
  approval.
- Each role sees its own home page and its own work.
- Picture: `inbox-middle.png`.

*Notes:* Eleven personas in the demo, two for every approving role, so both sides of four eyes can be played.

## 4. Trading

- Forwards, spots and futures; fixed or index prices; brokers, master agreements, intercompany mirrors.
- Pre-trade limit checks, approval in the inbox, confirmations matched field by field.
- Pictures: `trades-new.png`, `trades-detail.png`.

*Notes:* The capture form checks what it can; the server validates everything and explains refusals per field.

## 5. Inventory and allocation

- Lots with their full attribute set and a hash-chained journal; the expiry ladder.
- A criteria builder shared by trades and searches.
- Allocation: coverage as a maximum flow, a greedy or an exact (CP-SAT) solver, what-if without storing anything.
- Pictures: `inventory-lots.png`, `allocation-workbench.png`.

*Notes:* An approved plan reserves the lots and creates the registry instructions.

## 6. GoO portfolio management

- The problem: a sale can be specific ("Nordic hydro 2026") or generic ("any EEA renewable 2026"). A specific
  certificate can deliver a generic sale, but not the reverse; delivered that way, the premium goes to the buyer for
  free.
- Allocation is priced in EUR: each certificate at the best price it qualifies for, the give-away reported per
  sale.
- A product tree from proven containment, netted through the flow: a book can look long in "Nordic renewable" and
  be short there.
- Available to sell: how much more of a product could be sold without taking from existing sales; checked while a
  trade is captured.
- Future supply: open purchases and expected own issuance count as supply in tiers; plans reserve against them.
- Portfolio value next to the official valuation, with the give-away; GoO limits on concentration, expiry and basis.
- Pictures: `positions-products.png`, `positions-headroom.png`, `valuation-portfolio.png`.

*Notes:* Start from the sale that delivers a generic product with a Nordic hydro certificate, and show the give-away
on the what-if. The Products view shows the gaps that a plain position nets away. Available to sell answers the
trader's question before the trade is booked. The portfolio value is a management view; official valuation, the
ledger and credit stay on one price per product curve.

## 7. Registry and reconciliation

- Instructions stored first and sent afterwards, never inside a database transaction; retries are safe.
- Statements reconciled with our books; each difference is a break with an owner and an outcome.
- Pictures: `registry-instruction.png`, `reconciliation-breaks.png`.

*Notes:* Registry mappings that are not checked against the registry's own specification are marked unverified.

## 8. Market data, valuation and risk

- Quotes, FX and indices from one place; curves become official after a second person signs them off.
- Official valuation runs and P&L explain between runs.
- Limits, utilisation, breaches, credit support and exposure.
- Pictures: `valuation-runs.png`, `risk-limits.png`.

## 9. Settlement and EU ETS

- Deliveries become settlement items, then invoices (four eyes), payments and netting; VAT rules including reverse
  charge.
- EU ETS compliance: emissions, free allocation, surrenders and deadlines per installation.
- Pictures: `settlement-invoice.png`, `ets-overview.png`.

## 10. Finance and control

- Every event posts through posting rules; the journal is append-only and hash-chained.
- Month end: period end, soft and hard close with a checklist, the GL export and its acknowledgement.
- Audit trail of every change: who, when and why.
- Pictures: `monthend-close.png`, `audit-trail.png`.

## 11. Architecture: one application, eighteen bounded contexts

- A modular monolith: one deployable and one database, with module boundaries the build verifies.
- Four groups of modules (trading; operations; finance and risk; platform), each module with its own schema.
- REST API with a committed OpenAPI contract, authorised by role and legal entity; web UI in the same jar.
- Outbox to the registries, exactly-once GL exports, events to Kafka after commit, OIDC identities, metrics and traces.
- Picture: an architecture diagram drawn on the slide.

*Notes:* Side effects across modules travel as domain events inside the command transaction; registry calls never
run inside a database transaction.

## 12. Reliability: designed to targets, measured, rehearsed

- Targets: 99.9 % availability in business hours, RPO 15 minutes, RTO 4 hours, read p95 under 300 ms.
- Measured with k6: 556 requests/s with 104 users and no errors; read p95 15 ms at baseline load.
- Stateless and horizontally scalable; PostgreSQL as a three-instance CloudNativePG cluster; a monthly DR drill that
  verifies the hash chains; metrics, traces and structured logs.

*Notes:* The rehearsal still open is a drill against a production backup.

## 13. Security: least privilege, two people, a checked supply chain

- OIDC sign-in; every read and command checks role and legal entity.
- Four eyes on everything that moves value, in the services and again in the database.
- Strict CSP, CSRF, formula-safe exports; ZAP and accessibility checks.
- SBOMs for every build; a critical fixable vulnerability stops the image.

*Notes:* The external penetration test is prepared (scope, accounts, rules of engagement) and is the next step.

## 14. Robustness and data integrity

- Append-only, hash-chained lot journal, ledger and audit trail; corrections by reversal.
- Registry calls through an outbox; idempotent deliveries and GL exports; a timeout is reconciled, never resent.
- Bitemporal lots, prices and reference data; every report run logged with its content hash.
- Downstream problems are parked for attention; exact decimals only.

## 15. Maintainability: boundaries and contracts the build enforces

- Each module owns its schema and exposes one API package; any other dependency fails the build.
- The OpenAPI document is committed and the web types are generated from it.
- 41 architecture decision records, arc42 and a manual inside the application.
- Warnings are errors, static analysis, a coverage rule; `main` moves only on a green pipeline. Current stack: Java 25,
  Kotlin 2.4, Spring Boot 4, Gradle 9, React with TypeScript.

## 16. Roadmap: nine increments, each one shippable

- 1 core domain; 2 registries, settlement, inbox; 3 market data, valuation, risk, ETS, period end; 4–5 the web UI;
  6 operations at scale; 7 the showcase; 8 GoO portfolio management; 9 housekeeping and this documentation.

## 17. Path to production

- Assurance: the external penetration test, a DR drill on a production backup, ETS deadline rules checked.
- Specifications: registry APIs (G-REX, NECS) and AIB code lists, the GL file layout, the EMIR layout and trade
  repository, market data vendors.
- Sign-offs: accounting policies, VAT rules, PFE add-ons.
- Next to build: faster portfolio refresh, device caps in the flow, GoO limits per vintage, discounting and hedge
  accounting, EUA futures margining.

*Notes:* Layouts not checked against their specification are marked unverified.

## 18. In numbers, and try it

- Automated tests at every level (counts on the slide), API operations with a screen, decision records.
- Try it: the demo with guided tours and a manual in the app, or `ops/demo` with Docker on your own server.
- Picture: `guide.png`.

*Notes:* Close with the demo URL and the repository.
