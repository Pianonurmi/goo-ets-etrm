# Increment 7: public showcase demo

## Context

Increments 1–6 are delivered and green. The user wants to show the ETRM publicly as a proof of concept:

- Every feature should be triable through the web UI, which is how the system is meant to be used: trades are submitted there.
- Portfolios should be full of realistic trades, positions and history.
- The demo runs on a VPS behind a login.
- A deck of about 10 slides, an in-app tutorial, an in-app help manual, and repo instructions with pictures.

The whole suite must also look and feel state of the art. Today the UI is functional, accessible and consistent, but it uses Mantine defaults: system font, default colours, no brand, plain home page, plain empty and loading states.

The user decided:

- **Visual direction:** calm professional, in the spirit of Linear, Stripe and Vercel dashboards. Light-first with a polished dark mode. I propose the product name, logo and palette.
- **Login:** a login page with demo accounts from env, then the existing persona switcher (trader, middle office, risk, and so on). No Keycloak.
- **Deck:** a shareable slide artifact, exportable to PPTX/PDF, built from real screenshots.
- **Data reset:** nightly wipe and re-seed, plus an admin reset button.
- **Domain:** none yet. Caddy serves plain HTTP on the IP first and turns on HTTPS when a domain is set.

What the code does today:

- **Persona headers:** they work only under the `dev` profile. That profile permits every request, and a missing `X-Roles` header means ADMIN. Nothing stops it running on a public server, and `HeaderActorResolver` is the fallback in every profile.
- **Demo seeder** (`apps/api/.../demo/`):
  - It needs `dev`.
  - It has no reset, and a failure leaves partial data.
  - Its data is thin: 10 trades, no futures or index trades, no executed allocation, and DEMO-SE is almost empty.
- **UI gaps:** 70 of 288 API operations have no UI caller. For example, receiving certificates and recording a delivery or settlement can only be done through the API.
- **Trade form gaps:** it cannot send `priceType` INDEX, `priceIndex`, `futuresContractCode`, `brokerId`, `masterAgreementRef` or `mirrorBookId`.
- **Help and docs:** there is no help, tour or markdown library. The repo has no screenshots and no reverse-proxy config, and the image is not published.
- **Bundle budget:** the first page is 312 of 330 kB gzipped and the total is 921 of 1000 kB, so everything new must be lazy-loaded.

## Key design choices

| Topic | Choice |
|---|---|
| Visitor login | New `showcase` profile. A session cookie plus a JSON form login on `/api/v1/auth/login`, with CSRF via `csrf().spa()`. Demo users come from `ETRM_DEMO_USERS` (`user:pass,…`, BCrypt-hashed at start) or `ETRM_DEMO_PASSWORD` (user `demo`). Weak passwords are refused at start. The persona travels as `X-Persona: <id>`, and the server resolves its roles from its own catalogue. `X-Actor`/`X-Roles` are ignored. |
| Persona catalogue | One server-side `Personas.kt`, served in `/api/v1/ui-config`. `DemoUser` and the web read it. This also fixes the risk/risk2 role mismatch. |
| Seeder without `dev` | A random 256-bit seed token made per start and kept only in memory. `DemoClient` sends it as `X-Etrm-Seed`. The token is the secret; the loopback check is defence in depth and uses the socket address, never forwarded headers. Caddy must not trust client-supplied `X-Forwarded-For` (its default). The seeder keeps going through the REST API and all of its checks. |
| Protecting `dev` | `HeaderActorResolver` is gated to dev/test. `application-dev.yml` sets `server.address: 127.0.0.1`, so `bootRun`, Vite's proxy and Playwright keep working unchanged. A `ProfileGuard` (EnvironmentPostProcessor) refuses `dev` combined with `showcase` or `kubernetes`, and refuses `dev` on a non-loopback address unless `ETRM_DEV_ALLOW_REMOTE=true` (set only in the local `ops/docker-compose.yml`). |
| Reset | In showcase, every start wipes all `etrm_*` schemas, then migrates and seeds. It refuses unless `current_database()` equals `ETRM_SHOWCASE_DATABASE`. A reset is a controlled process exit (code 75) and Docker `restart: unless-stopped` brings the app back. A nightly ShedLock job runs at 04:15 Europe/Helsinki. The admin button needs the ADMIN persona and a reason, and is refused within 10 minutes of start. Wiping inside a running app is rejected: caches, jobs mid-run and event publications make it unsafe. |
| Help renderer | `markdown-to-jsx` with raw HTML disabled, inside the lazy help chunk only. The CSP is untouched. |
| Tour | A small homegrown component: `@floating-ui/react` (already a Mantine dependency) plus a Mantine Overlay, lazy-loaded. |
| Image | CI pushes to GHCR on `main` after the Trivy gate; the VPS pulls it. |
| Design system | Built on the existing Mantine theme (`app/theme.ts`), not a new UI library. Inter as a variable font, self-hosted (CSP `font-src 'self'`). Tabular numerals for all figures. One accent colour plus neutral greys, with semantic colours for gain, loss, warning and status, all kept at WCAG AA in both modes. Tokens for spacing, radius, shadow and motion. AG Grid themed through its theming API to match. |

## Milestones

Every milestone ends the same way as before:
- a green `./gradlew build` (ktlint, detekt, Kover);
- a green `:apps:web:check`;
- Playwright on a fresh database, including the new `showcase` project from M1;
- axe on the new UI, `npm run budget`, and the OpenAPI snapshot updated;
- docs, then a commit (as the maintainer), then a push only after the previous CI is green.

### M0: Foundations
- `apps/web/scripts/check-api-coverage.mjs`, run by `npm run check`.
  - It maps OpenAPI operation ids to the call sites in `src/`.
  - It fails on any operation that has no caller and is not listed in `api-only.json`, which records a reason for each entry.
- Persona catalogue (`apps/api/.../config/Personas.kt`) sent in `ui-config`. `personas.ts` reads it, with the static list kept as a test fixture.
- `HeaderActorResolver` limited to dev/test; `server.address: 127.0.0.1` in `application-dev.yml`; `ProfileGuard`.

### M0b: Design system and app shell (ADR 0034)
Comes before every new screen in this increment, so the login page, help, tours and new actions are built on it.

**Approval gate:** implementation pauses after the design board until the brand and tokens are approved.

**Brand proposal**
- Product name, SVG wordmark and logo mark, favicon, palette and type scale.
- Presented as a one-page design board (a design artifact) for approval before code changes.

**Theme** (`app/theme.ts` plus `app/tokens.ts`)
- Typography: Inter, type scale, tabular numerals.
- Colour: accent and neutrals; semantic tokens for buy/sell, gain/loss and each status.
- Spacing, radius, shadows, focus rings, motion with `prefers-reduced-motion` honoured.
- Dark mode tuned, not just inverted.
- AG Grid theme: row height and density options, number alignment, coloured P&L, sticky totals.

**Shared components** (`shared/components/`)
- `PageHeader` v2: breadcrumbs, title, status badge, primary and secondary actions.
- `KpiCard` with a sparkline, `StatusBadge` (one colour map for every lifecycle), `Money` and `Quantity` with units.
- `EmptyState` with an illustration and a call to action; skeleton loaders in place of spinners; `DetailLayout` (summary card, tabs, side panel).
- Toasts and confirmations made consistent.

**App shell**
- Collapsible sidebar with icons and section labels.
- Top bar: entity selector, search (palette), help "?", inbox bell with count, persona and user menu.
- Density toggle (comfortable or compact), saved per user.
- Keyboard shortcuts sheet (`?` key).
- Responsive down to a tablet width.

**Home**
- A role-aware dashboard instead of the intro text:
  - KPIs: open tasks, positions, MtM and P&L, coverage, upcoming deadlines;
  - "My work" from the inbox;
  - recent activity.
- Charts with Mantine charts and recharts, both already in the bundle.
- Backend: one summary endpoint per role (`GET /api/v1/home/summary`) that aggregates existing queries with entity scope, so the home page needs one call. OpenAPI snapshot updated.

**Guards**
- Tokens and components documented in `docs/ui/design-system.md` with a screenshot board.
- The axe and contrast checks stay green in both modes.
- Bundle: the font is about 30–40 kB as WOFF2 and is outside the JS budget; the first-page JS must stay under 330 kB.

### M1: Showcase authentication (ADR 0032)

**Backend**
- `ShowcaseSecurityConfiguration` (`@Profile("showcase")`). `resourceServerChain` becomes `!dev & !test & !showcase`.
- Login and logout endpoints, CSRF, session fixation protection, a 401 entry point for `/api/**`.
- `LoginThrottle`: 5 failures per user or IP in 15 minutes, and a global cap; a 429 problem with `Retry-After`; a dummy BCrypt check for unknown users.
- `SeedTokenFilter` and actor resolution in this order: seed, showcase session plus `X-Persona`, JWT, then dev headers.
- The login name goes into MDC and the logs. The audit `userId` stays the persona id; this is documented.
- `application-showcase.yml`:
  - forwarded headers and remote-IP from the Docker network;
  - an 8 h session with `SameSite=Lax`, http-only and secure-by-forwarded-protocol cookies;
  - API docs closed.
- `ui-config` gains:
  - `authMode: SHOWCASE`;
  - `personas`;
  - `demo {enabled, seed {status, step, finishedAt}, nextResetAt}`.
- `/me` gains `login`.
- An optional entity-scoped persona `trader-se` to show legal-entity scope.

**Web**
- `session.ts` gets a SHOWCASE branch: a 401 from `/me` leads to the login page; `X-Persona` and `X-XSRF-TOKEN` headers are sent.
- A lazy `LoginPage` with a short explanation: personas, the shared sandbox (other visitors see your changes), the nightly reset, and that all companies, counterparties and prices are fictitious. A teal DEMO banner, `PersonaMenu` shown in showcase, and a user menu with the login name and Sign out.
- A "Preparing demo data… step x/y" overlay while seeding.

**Tests**
- `ShowcaseSecurityIT`: CSRF is required; `X-Actor` is ignored; an unknown persona gets 403; 429 after 5 failures; the seed token is refused off loopback.
- Playwright `showcase` project (`e2e/showcase.spec.ts`) on its own database `etrm_showcase_e2e` (added to `ops/postgres-init.sql`), run after the default project since both use one worker: log in, capture as trader, switch persona, approve as middle; wrong password then lockout; axe on the login page.

### M2: Seeder restructure
- `DemoStep(name, run(DemoContext))` steps in an ordered `DemoPlan`, with `DemoContext` carrying `DemoCalendar` and the ids created so far.
- Run log in an `etrm_demo` schema (`seed_run`, `seed_step`), migrated only under the `demo` profile and written with `JdbcClient`.
- Completed steps are skipped on a re-run. The run stops at the first failure and records the request, response, duration and status FAILED.
- The seeder runs under dev, test or showcase, synchronously on ApplicationReadyEvent, so readiness stays down until it finishes.
- Seed status is exposed in `ui-config`.
- Fewer calls: market data history as CSV imports, `workflow` bulk approvals, deterministic price walks (`Random(42)`). Moved to M3, with the dataset that needs them (today's seed takes about 12 s).
- Tests: `DemoDataSeederIT` extended and `DemoSeedRunnerIT` (resume after a failure). Seeding under `showcase` with the seed token is covered by the Playwright `showcase` project, so no separate `ShowcaseSeedIT`.

### M3 and M4: Filled portfolios
Six months of history, with all dates relative to today (`DemoCalendar`). The order is:
1. reference data
2. market data
3. inventory
4. trades
5. confirmations
6. allocation and registry
7. deliveries and settlement
8. ETS
9. valuation
10. risk
11. ledger months, oldest first: period end, export, acknowledgement, close
12. positions
13. EMIR
14. open inbox tasks, last

| Area | Target |
|---|---|
| Entities | DEMO-FI (EUR) and DEMO-SE (SEK, so FX revaluation shows); 3 books each |
| Counterparties | 8 (NO, DE, FI, SE, NL, FR, DK, ES): a broker, an exchange/clearing house, 2 with netting; bank accounts, VAT and addresses filled |
| Devices and lots | Own devices for hydro, run-of-river, wind, solar, nuclear and biomass, plus external devices in NO, DE, FR and ES. About 100 lots across states: expiring in 30, 60 and 90 days, expired, cancelled, reserved, in transfer, withdrawn, split. Support flags mixed. |
| Market data | EUA spot; EUA futures for 2 Decembers with daily settlements; GoO vintages for 4 buckets × 2 years; GoO index with monthly fixings; EUR/SEK, EUR/NOK and EUR/USD daily; one suspect quote |
| Curves | EUA forward plus 4 GoO curves, official at every month end and the last 5 business days; one snapshot pending sign-off |
| Trades (~45) | GoO forward and spot with varied criteria, 2 index-priced, EUA forward and spot, 3 EUA futures by contract code, an intercompany FI→SE mirror, broker on 6 trades. Option, swap and auction only where `TradeValidation` accepts them; check it first. States: pending approval, confirmed, delivered and settled, amended (v2), cancelled, and one refused by the pre-trade check. |
| Allocation and registry | 2 executed plans whose instructions are confirmed, 1 plan proposed; share constraints, a manual obligation; about 12 confirmed transfers, 1 pending, 1 failed with a follow-up task, 1 Union Registry allowance transfer, last year's surrender |
| Settlement | About 20 invoices: paid, partly paid, overdue (for ageing), a credit note; purchase invoices matched; bank CSV with unapplied cash; netting settled, agreed and draft; reverse-charge VAT; a pending VAT rule |
| Confirmations and reconciliation | Most confirmations matched, one mismatched, one disputed. Statements for every account; breaks open, resolved and pending acceptance. |
| ETS | Installations in FI and SE; verified emissions approved, plus one pending; monthly estimates; free allocation; surrender; deadlines |
| Valuation, risk, ledger | Official EOD runs at each month end and the last 5 days (P&L explain across runs), one run pending. Limits for both entities, one breach open and one acknowledged. Period end for M-6 to M-1. GL exports acknowledged up to M-2; M-1 not acknowledged (shows as a blocker). M-6 to M-3 hard closed, M-2 soft closed. A manual accrual and a reversal, a GL balance import with one difference, a pending rule change and a pending policy change. Position snapshots at month ends. |
| EMIR | NEWT, MODI, TERM, VALU, one EROR, and 3 export runs |
| Inbox | At least 1 actionable task for every persona (asserted in the IT) |

Targets:
- The seed runs in under 120 s on 2 vCPUs, measured in the IT. If the full dataset cannot meet that, the nightly window simply takes longer (readiness stays down, Caddy shows the maintenance page) and the admin reset dialog states the expected duration. Fallback if it grows past about 5 minutes: wipe-on-start restores a `pg_dump` taken after the last good seed, and only the nightly job runs the seeder.
- The IT asserts counts per area.

**Spike first (start of M3, before the dataset is promised):** confirm with small ITs that the backend accepts what the dataset needs: back-dated valuation runs (`asOf`), period-end runs and GL exports for past months, hard close with its blocker checklist, which trade family and instrument combinations `TradeValidation` allows, and index-priced trades with fixings. Trim the dataset table to what passes.

Risk: business dates in the past against records stamped today. Do every business event first, then close months last, oldest first.

### M5: Reset (ADR 0033)
- `ShowcaseWipe`, hooked into `ModularMigrations` before Flyway, with the database-name guard.
- `ShowcaseResetService`, `POST /api/v1/showcase/reset`, and `ShowcaseNightlyReset` (cron `etrm.showcase.reset-cron`).
- Admin UI: an "Administration → Demo" card with `ReasonModal`, a type-RESET confirmation, and a restart overlay that polls until the seed is READY and then returns to the login page.
- `ShowcaseWipeIT`: a wrong database name is refused; schemas are dropped and migrated again.

### M6: VPS stack and first deployment
- `ops/demo/` holds:
  - `docker-compose.yml`:
    - postgres 17 with tuned memory and no published port;
    - the app from GHCR with `showcase,demo,json`, `read_only`, `tmpfs /tmp:exec`, `mem_limit` about 2.5 GB, `restart: unless-stopped`;
    - caddy 2.
  - `Caddyfile`: `ETRM_SITE_ADDRESS` defaults to `:80`; HTTPS is automatic once a domain is set. Also `noindex` and `robots.txt`; `/actuator`, `/v3/api-docs` and `/swagger-ui` return 404; a body size limit; a readiness health check; a maintenance page on 502/503.
  - `.env.example`, `maintenance/index.html`, and `backup.sh` (daily `pg_dump`, keep 7, at a time that avoids the reset).
- `ci.yml` pushes `ghcr.io/<owner>/goo-ets-etrm:main` and `:<sha>` on `main` after the Trivy gate.
- `docs/deploy/vps.md`:
  1. Ubuntu 24.04, 4 GB RAM, 2 vCPUs; SSH keys, ufw (22, 80, 443), unattended upgrades.
  2. Install Docker; set up `.env` with secrets and `chmod 600`.
  3. `docker compose up -d`.
  4. A first look over HTTP on the IP; the doc warns that passwords travel unencrypted.
  5. Add a domain for HTTPS later.

  Also covered: updates, manual reset, backup and restore, logs, troubleshooting, and a security checklist.
- Verify:
  - curl smoke: `X-Robots-Tag` present, actuator returns 404, login works, a persona switch works;
  - a reset loop;
  - a manual ZAP baseline against the showcase.

### M7: UI completeness, trading
- Trade form (`features/trades/TradeFormPage.tsx`, `model/tradeForm.ts`):
  - all instrument types;
  - price type FIXED or INDEX, with a `priceIndex` select for INDEX;
  - a FUTURE contract select (`product_futuresContracts`) that fills delivery, ISIN and UPI;
  - broker, master agreement reference;
  - an intercompany `mirrorBookId` when the counterparty is one of our own entities;
  - a "Check limits" button that calls `risk_preTradeCheck`.

  Visibility rules stay pure functions with Vitest.
- Trade detail:
  - record delivery and settle;
  - for EUA legs, "Deliver allowances" and "Match receipt";
  - a Documents tab with hash verification.
- Playwright: futures, index and intercompany journeys.

### M8: UI completeness, everything else
Reuse `Can`, `permissions.ts`, `ReasonModal`, `uploadText`, `ProblemAlert` and `applyProblemToForm`, plus a shared `JsonEditorModal` for format JSON. New actions:

- **Inventory:** receive certificates; lot reserve, release, correct and expire; verify the journal chain; allowance movements; the expiry ladder chart.
- **Allocation:** open and close obligations; share constraints editor.
- **Market data:** new instrument, aliases, indices and fixings, import format editor.
- **Curves and valuation:** new curve; "official as of" for curves and for valuation runs.
- **Positions:** snapshots tab, list and take.
- **Reference data:** edit legal entity and counterparty; create and edit device; default book.
- **Registry:** Union Registry allowance transfer; GoO cancellation from a lot; statement format editor.
- **Ledger:** propose a posting rule change; CSV export.
- **ETS:** installations list.
- **EMIR:** "Record rejection".
- **Inbox:** the requester can cancel a task.
- **Settlement:** item detail drawer.

`api-only.json` shrinks to the operations that should stay API-only, each with its reason, also listed in `docs/ui/screens.md`. One example is `registry_surrender`: the UI goes through the ETS surrender request, which carries attribution. Playwright and axe cover each area.

### M9: Screen-by-screen design pass
Runs before the help, tours and screenshots, so the help text, tour anchors and pictures describe the final screens. Every screen in `docs/ui/screens.md` moves to the M0b components and patterns:
- page headers;
- KPI strips on overview pages: trades, positions, allocation, risk, ETS, settlement, month end;
- detail pages using `DetailLayout`, with timelines for lifecycles (trade, instruction, invoice, plan);
- empty states and skeleton loaders;
- consistent number formats and status colours;
- forms in sections with inline help;
- charts where they explain more than a table: expiry ladder, coverage, P&L explain, ageing, ETS compliance.

Method:
- Before/after screenshots for every screen, from the screenshot spec (M12) run early, reviewed by eye.
- A short heuristic checklist per screen:
  - the primary action is obvious;
  - the most important figure is first;
  - no more than one dense grid above the fold;
  - errors are actionable;
  - it works at 1280 px and on a tablet.

Tests: existing Playwright selectors kept stable (`data-testid`), so the e2e suite stays green; axe in both modes.

### M10: In-app help manual
- `docs/user-guide/*.md` is the single source (one page per area, plus the glossary from `docs/glossary.md`).
- Each page becomes a lazy chunk via `import.meta.glob(..., {query: '?raw'})`.
- Images:
  - a Vite plugin copies `docs/images` to `build/dist/help/images`;
  - `SpaConfiguration` serves `/help/images/` as static.
- `HelpDrawer`:
  - opened by the "?" in the header or `shift+?`;
  - picks the page for the current screen from a route→page#anchor map;
  - has a full-manual route `/help/:page` and a "Start the tour for this screen" button.
- Help search in `CommandPalette` from a build-time `help-index.json`.
- An image size check: each image at most 300 kB, all images at most 12 MB.

### M11: Tutorial and demo guide
- Under `shared/tour/` (lazy):
  - **`TourHost`:** finds the anchor through `MutationObserver` and `data-tour` attributes; spotlight plus overlay; a floating popover with `role=dialog`, focus trap, Esc and arrow keys; a "Switch to <persona>" button when a step needs a different persona; a centred fallback with Skip when the anchor is missing.
  - **Progress:** kept in localStorage.
  - **Texts:** a lazy i18n namespace `tour`.
- **11 tours:**
  1. orientation
  2. capture and approve an index trade
  3. confirmations
  4. inventory and criteria
  5. allocate and deliver
  6. curves, valuation and P&L explain
  7. risk
  8. ETS
  9. invoice, pay and net
  10. month end
  11. admin and audit
- `/guide`: a demo guide with scenario cards (time, personas, a progress tick, Start), linked from the palette, the help menu and the welcome modal.
- Welcome modal on first login: personas, four eyes, the nightly reset; Start orientation, Open guide or Skip.
- Playwright runs every tour to the end as the right personas; axe on an open tour step.

### M12: Screenshots, user guide and README
- `apps/web/e2e/docs/screenshots.spec.ts`, in a separate Playwright `docs` project:
  - It runs the jar with `showcase,demo` on database `etrm_docs` (added to `ops/postgres-init.sql`).
  - It logs in properly, so no dev banner appears.
  - 1440×900, light mode plus one dark hero shot, animations off.
  - About 40 PNGs to `docs/images/<area>-<screen>.png`.
- A manual or weekly `docs-screenshots.yml` workflow uploads the images as an artifact; refreshing the committed images is a manual step.
- `docs/user-guide/`:
  - pages: getting started, trading, confirmations, inventory, allocation, positions, registry, reconciliation, settlement, market data and curves, valuation, risk, ETS, ledger and month end, reports, administration and audit;
  - each page has role workflows, screenshots and "Try it in the demo" boxes.
- README overhaul:
  1. hero screenshot and pitch;
  2. try the demo;
  3. feature table with 6 screenshots;
  4. Quick start A, the showcase locally via `ops/demo`;
  5. Quick start B, the developer build;
  6. architecture and modules;
  7. links to the user guide, deployment guide and presentation.
- Docs updated:
  - `docs/ui/README.md`: auth modes, tours, help.
  - `docs/ui/testing.md`.
  - arc42 sections 7 and 8.
  - Security notes: the showcase threat model, shared personas, the shared-data caveat.
  - The roadmap.

### M13: Deck and close
- `docs/presentation/outline.md`: 10 slides, each with content, speaker notes and image names.
  The deck uses the M0b brand: name, logo, palette and type.

  1. Title and hero shot.
  2. The problem: certificates as number ranges with attributes, criteria, registries, four eyes, compliance.
  3. Coverage and the module map, with the inbox.
  4. Trading: form, futures and index pricing, confirmations.
  5. Inventory and allocation: criteria builder, CP-SAT what-if.
  6. Registry and reconciliation.
  7. Market data, valuation and risk.
  8. Settlement and ETS.
  9. Finance and control: month end, GL export, hash-chained audit.
  10. Engineering and "try it": tests, security, Helm, Kafka, k6, demo URL.
- Build the slide artifact from it with embedded screenshots, exportable to PPTX/PDF, and link it from the README.
- Roadmap: increment 7 delivered.
- Final redeploy on the VPS. Smoke test as a fresh visitor: welcome modal, the orientation tour, one full scenario.

## Minimum before the first external visitors

M0 to M9 plus the screenshots of M12. Help, tours and the deck can follow while the first visitors already use the demo. The roadmap row stays "in progress" until M13.

## Accepted risks

- **Shared sandbox.** Every visitor can pick the ADMIN persona and change shared data until the nightly reset. The reset cooldown, the reset-user allowlist and the login notice limit the damage. Any crash or redeploy also resets the data.
- **Write abuse.** A visitor could script thousands of trades. There is no write throttle; the nightly reset and `mem_limit` contain it. A per-session write limit is an option if it happens.
- **Audit identity.** The audit trail records the persona, not the visitor's login; the login is only in the access log and MDC. Two visitors acting as the same persona count as one person for four-eyes checks. Both are stated in the guide.
- **Plain HTTP on the IP.** The password travels unencrypted until a domain is set. The deploy guide says so.
- **Seed duration.** See M3: a long seed means a longer maintenance window, not a failed demo.
- **Scope.** Fourteen milestones with a full CI run (about 25 minutes) between pushes. The milestone order puts the deployable demo first.

## Critical files

**Modify**
- `apps/api/.../config/SecurityConfiguration.kt`
- `config/ModularMigrations.kt`
- `web/UiController.kt`, `web/UiProperties.kt`, `web/SpaConfiguration.kt`
- `demo/DemoDataSeeder.kt`, `DemoClient.kt`, `DemoScenario.kt` (split into steps)
- `apps/web/src/shared/auth/session.ts`, `personas.ts`
- `app/layout/Header.tsx`, `AppLayout.tsx`, `CommandPalette.tsx`
- `main.tsx`, `app/router.tsx`, `app/navigation.tsx`
- `features/trades/TradeFormPage.tsx`, `model/tradeForm.ts`
- `playwright.config.ts`, `e2e/fixtures.ts`, `vite.config.ts`, `package.json`
- `.github/workflows/ci.yml`, `ops/postgres-init.sql`, `ops/docker-compose.yml`
- `README.md`, `docs/roadmap.md`

**Create**
- `apps/web/src/app/tokens.ts`, `public/fonts/` (Inter WOFF2), logo SVGs, shared components (`KpiCard`, `StatusBadge`, `EmptyState`, `DetailLayout`, skeletons), `docs/ui/design-system.md`, ADR 0034
- `config/ShowcaseSecurityConfiguration.kt`, `Personas.kt`, `ProfileGuard.kt`
- `showcase/` (wipe, reset, nightly job)
- `application-showcase.yml`, `application-demo.yml`, `db/demo-migration/`
- `apps/web/src/app/pages/LoginPage.tsx`, `app/help/`, `shared/tour/`, `features/guide/`
- `scripts/check-api-coverage.mjs`, `api-only.json`
- `e2e/showcase.spec.ts`, `e2e/docs/screenshots.spec.ts`
- `ops/demo/*`
- `docs/user-guide/*`, `docs/images/*`, `docs/deploy/vps.md`, `docs/presentation/outline.md`
- ADR 0032 and ADR 0033

## Verification

**Per milestone**
- Run the gates listed under Milestones; the commands are:

  ```bash
  ./gradlew build
  ./gradlew :apps:web:check
  npx playwright test --project=default --project=showcase
  npm run budget
  ```
- Seed time is logged by the seeder and asserted in `DemoDataSeederIT` and `ShowcaseSeedIT`.

**Locally, before the first deployment**
- `ops/demo` with `docker compose up` and `ETRM_SITE_ADDRESS=:8088`:
  - log in, switch persona, run a tour, trigger a reset and watch it restart;
  - curl checks: robots header present, actuator returns 404.

**On the VPS (M6 and M12)**
- Smoke test as a fresh visitor:
  - welcome modal, orientation tour, demo guide scenario "Capture and approve a trade";
  - help drawer on three screens;
  - admin reset, then the data is back after the restart.
- The nightly reset is visible in the logs the next morning.
- A ZAP baseline against the public URL shows no failures.
