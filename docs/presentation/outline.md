# Provena: presentation outline

Ten slides for a 15-minute introduction. The deck uses the Provena brand (`docs/ui/design-system.md`). Its
pictures come from `docs/images` (`npm run docs:screenshots`). Speaker notes follow each slide.

The slides are published as a deck on claude.ai, built from this outline and the screenshots; download it as
PowerPoint or PDF from the deck's Share menu: <https://claude.ai/artifact/NHG4trUF1EkTnB6dXmAxyv>. When the screens
change, retake the screenshots and replace the pictures in the deck.

## 1. Provena: certificates and allowances, from trade to ledger

- A trading and risk system for guarantees of origin and EU emission allowances.
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

## 6. Registry and reconciliation

- Instructions stored first and sent afterwards, never inside a database transaction; retries are safe.
- Statements reconciled with our books; each difference is a break with an owner and an outcome.
- Pictures: `registry-instruction.png`, `reconciliation-breaks.png`.

*Notes:* Registry mappings that are not checked against the registry's own specification are marked unverified.

## 7. Market data, valuation and risk

- Quotes, FX and indices from one place; curves become official after a second person signs them off.
- Official valuation runs and P&L explain between runs.
- Limits, utilisation, breaches, credit support and exposure.
- Pictures: `valuation-runs.png`, `risk-limits.png`.

## 8. Settlement and EU ETS

- Deliveries become settlement items, then invoices (four eyes), payments and netting; VAT rules including reverse
  charge.
- EU ETS compliance: emissions, free allocation, surrenders and deadlines per installation.
- Pictures: `settlement-invoice.png`, `ets-overview.png`.

## 9. Finance and control

- Every event posts through posting rules; the journal is append-only and hash-chained.
- Month end: period end, soft and hard close with a checklist, the GL export and its acknowledgement.
- Audit trail of every change: who, when and why.
- Pictures: `monthend-close.png`, `audit-trail.png`.

## 10. Engineering, and try it

- Kotlin and Spring Modulith (one module per bounded context), PostgreSQL, React. More than 600 automated tests
  (about 320 backend unit and integration tests, 234 UI unit tests, 77 Playwright journeys, October 2026),
  accessibility checks and load tests.
- Security: four eyes in the services and the database, entity scope, CSP, signed-in showcase, vulnerability gate
  in CI.
- Runs on Kubernetes (Helm) or one server with Docker (`ops/demo`); events to Kafka.
- Try it: the demo, with a guided tour and a manual in the app.
- Picture: `guide.png`.

*Notes:* Close with the demo URL and the repository.
