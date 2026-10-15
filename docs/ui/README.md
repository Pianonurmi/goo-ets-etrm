# Web UI

React and TypeScript single-page application in `apps/web`, served by the API jar from the same origin (ADR 0025).
Sign-in uses OIDC with PKCE in production and dev personas in the `dev` and `test` profiles (ADR 0026). The public
showcase (`showcase` profile, ADR 0032) has a sign-in page with demo accounts, then the same persona menu. The API
client is generated from the committed OpenAPI document (ADR 0027). Actions are offered by role through a table
keyed by operation id (ADR 0028). The look, tokens and shared components are in [design-system.md](design-system.md)
(ADR 0034).

## Run it

```bash
# Backend with dev personas (PostgreSQL as in the main README), then the Vite dev server with hot reload:
SPRING_PROFILES_ACTIVE=dev,demo ./gradlew :apps:api:bootRun
cd apps/web && npm ci && npm run gen:api && npm run dev     # http://localhost:5173, proxies /api to :8080

# Or the bundled build: the jar serves the UI at http://127.0.0.1:8080/ (the dev profile listens on loopback only)
./gradlew :apps:api:showcaseJar && java -jar apps/api/build/libs/api-showcase.jar --spring.profiles.active=dev,demo
```

The showcase locally (any password of 12 characters or more; sign in as `demo`):

```bash
ETRM_DEMO_PASSWORD=choose-a-long-one java -jar apps/api/build/libs/api-showcase.jar --spring.profiles.active=showcase,demo
```

| `ui-config` auth mode | Profiles | Sign-in | Acting as | Banner |
|---|---|---|---|---|
| `OIDC` | production | identity provider (PKCE) | the signed-in user | none |
| `DEV_HEADERS` | `dev`, `test` | none | persona menu, `X-Actor`/`X-Roles` | orange |
| `SHOWCASE` | `showcase` | demo account, session cookie and CSRF | persona menu, `X-Persona` | teal |

With the `demo` profile as well (`dev,demo`), the first start fills an empty database with demo data through the
REST API (`DemoDataSeeder`), about six months of business in date order:
- **Parties:** `DEMO-FI` (EUR) and `DEMO-SE` (SEK) with books, simulator and Union Registry accounts, four devices
  each, and eleven fictitious counterparties (two with netting, a broker, a clearing house, the two entities as
  intercompany counterparties) with addresses, VAT numbers and master agreements.
- **Market data:** sixteen months of daily FX and seven months of EUA spot, two December futures and GoO vintages for
  four buckets, imported as files (one GoO print flagged suspect); a GoO index; official curves at every month end and
  on the last five business days, one more waiting for `risk2`.
- **Trading:** about 45 trades: GoO forwards and spots across buckets, EUA forwards and futures, two index-priced
  sales, an intercompany sale with its mirror, a broker, an amendment, a cancellation, trades waiting for approval;
  counterparty confirmations matched, mismatched (for `middle2`) and disputed.
- **Certificates:** a year of issuance; lots held, reserved, split, withdrawn, expired, cancelled, in transfer and
  transferred out.
- **Allocation and registry:** two executed plans (one cancelling for own consumption), one waiting for approval;
  Union Registry allowance transfers confirmed and failed, last year's surrender confirmed, a manual GoO transfer
  waiting for a second person.
- **Finance:** month-end valuations, position snapshots and period ends for both entities; months six to three
  hard closed, the month before last soft closed and acknowledged, last month exported but not acknowledged (a
  hard-close blocker); an accrual, a reversal, GL balances with one difference, a policy change and a rule change
  waiting; invoices paid, partly paid, overdue and credited; a settled netting (Vinden) and a draft (Nordvind); a
  VAT rule waiting; an index-priced delivery parked until its price is fixed.
- **Risk and compliance:** limits for both entities with an open breach (DEMO-SE) and an acknowledged one, a
  valuation waiting for sign-off; ETS installations in both countries with estimates, verified emissions (one
  waiting) and free allocation; EMIR reports including an error and three exports; reconciliation breaks open,
  resolved and waiting for acceptance.
- **Inbox:** every approving persona has work waiting.

The seeder runs as named steps (`DemoScenario.steps()`) and logs each run and step in schema `etrm_demo`, which only
the `demo` profile migrates. A step that fails stops the run with the failed request and response in the log; the
next start resumes at that step with the same dates, and the values earlier steps produced come back from the log
(`DemoContext`). Until the run ends the application is not ready (readiness probe down), and `ui-config` reports
`demoSeed` progress, which the UI shows as "Preparing the demo data". It does nothing once a run has completed (or
when `DEMO-FI` exists from before the run log). Drop the database to start over.

`ETRM_API_URL` points the Vite proxy elsewhere. `./gradlew build -Petrm.skipWeb` builds the backend without the UI.

## Checks

| Command | What |
|---|---|
| `npm run gen:api` | TypeScript types from `apps/api/openapi/etrm-api.json` into `src/shared/api/schema.d.ts` (git-ignored) |
| `npm run typecheck` | `tsc` in strict mode |
| `npm run lint` / `npm run format:check` | ESLint (type-checked, React hooks, accessibility) and Prettier |
| `npm test` | Vitest with Testing Library and msw. `npm run test:coverage` (run by `check`) covers all of `src` and fails below the floors in `vite.config.ts`; a local run raises them when coverage grows, so commit that change |
| `npm run coverage:api` | Every API operation has a UI caller, or a reason in `api-only.json` (`apiOnly`, or `planned` with its milestone) |
| `npm run e2e` | Playwright journeys against the jar (`../api/build/libs/api-showcase.jar`, `dev,demo` profiles, database `etrm_e2e`) |

`./gradlew :apps:web:check` runs the first five and the bundle budget. `./gradlew build` runs them and puts the bundle into the jar.

For end-to-end tests:
- `E2E_DB_URL`, `ETRM_DB_USER` and `ETRM_DB_PASSWORD` choose the database.
- `E2E_BASE_URL` reuses a running server.
- `PW_CHROMIUM_PATH` uses an installed Chromium instead of `npx playwright install chromium`.

## Layout

```
src/app        entry, providers, router (lazy screens), shell layout, navigation, theme, i18n
src/shared     api (client, problems, downloads, generated schema), auth (OIDC, personas, permissions),
               context (legal entity), grid (AG Grid wrapper), format, forms, components
src/features   one folder per area: pages, components, model (pure logic with unit tests)
src/locales    translations (English)
e2e/           Playwright specs
```

A page with tabs keeps only its header, KPIs and tab list. Each tab, drawer and modal has its own file
(`LimitsTab.tsx`, `EntryDrawer.tsx`), queries shared by several of them sit in the folder's `hooks.ts`, and badges and
types they share in `shared.tsx`. Other pages import from those files, never from a page, so opening one screen does
not load another.

Screens, their operations and roles: [screens.md](screens.md).
Tests at every level: [testing.md](testing.md).

## Working in the UI

- **Command palette:** `Ctrl K` (`⌘ K`) or `/` opens a search over the screens the user may see. From two characters
  on, it also finds trades by number, external reference or UTI in the selected legal entity.
- **Filters in the URL:** lists keep their filters and tabs in the query string, so a view can be bookmarked or
  shared. Screens read and change it through `useUrlState()` (`set({ key: value })`, null removes a key) and
  `useUrlTab(tabs, default)` (`shared/useUrlState.ts`); values are narrowed with `oneOf`/`allOf` (`shared/urlParams.ts`).
- **Amounts on screen** render with `<Money>` (`shared/components/Figures.tsx`); `formatMoney` is for text that is not
  an element (grid formatters, labels, messages).
- **Legal entity:** the header selector scopes every screen. Its choice is kept per browser.


## Help, tours and the demo guide

- **Manual:** `docs/user-guide/*.md` is the one source for the repository and the app. The build copies the pages to
  `/help/pages/` and the screenshots of `docs/images` to `/help/images/`, and writes `/help/index.json` (titles and
  headings) for the palette search (`vite.help.ts`). They are static content, not part of the code bundle.
- The `?` button (or `Shift ?`) opens the page for the screen in view (`app/help/routes.ts`, route → page#heading);
  `/help/:page` shows the whole manual. Pages are rendered by `markdown-to-jsx` with raw HTML off.
- `npm run check:help` (in `check`) fails on a missing page, a link or heading that goes nowhere, or an image over
  300 kB (12 MB in all).
- **Tours:** `shared/tour/tours.ts` defines eleven tours. A step points at a CSS selector, preferably a
  `data-testid` (a contract the end-to-end tests keep stable), and may name the persona it is for; the card then
  offers to switch. `TourHost` (lazy) dims the page around the target, and the arrow keys and Escape work.
  Progress is kept in the browser. The texts are their own lazy namespace, `locales/tour.en.json`.
- **Demo guide:** `/guide` lists the tours with time, personas and what is done. In the showcase a first visit is
  greeted by `WelcomeModal`.
- **Screenshots:** `npm run docs:screenshots` (its own config, `playwright.docs.config.ts`) takes the pictures of
  the manual and the README from the showcase on database `etrm_docs`. Review them before committing.

## Conventions

- **API calls:** go through `$api` (TanStack Query) or `api` (openapi-fetch); paths and bodies are typed from the
  document. A failed call throws `ProblemError` with the server's problem.
  - Queries render their error with `QueryState`.
  - Mutations notify, unless they set `meta: { notify: false }` and map violations onto form fields with
    `applyProblemToForm`.
- **Amounts:** the server computes every amount. The UI formats numbers (`shared/format`) and sends decimals back as
  strings (`decimal()`), so nothing is rounded in the browser.
- **Permissions:** actions are shown or hidden with `<Can op="trade_approve">` and `useCan()`, keyed by operation
  id (`shared/auth/permissions.ts`). The roles come from the API: each handler names them in `@AllowedRoles`, the
  OpenAPI document publishes them as `x-roles`, and `npm run gen:api` generates `permissions.generated.ts`. The API
  checks again.
- **Legal entity:** the entity selected in the header (`useEntity()`) scopes the screens. Lists on the server are
  limited to the user's entities.
- **Text:** all visible text comes from `src/locales/en.json` through `useTranslation()`.
- **Large tables:** use `DataGrid` (AG Grid Community with the application theme). Small ones use Mantine `Table`.

## Accessibility, security and size

- **Contrast:** the theme meets WCAG 2.1 AA for normal text (`src/app/theme.ts`, `src/app/contrast.ts`). Light
  variants, filled buttons, coloured text and dimmed text are darkened where Mantine's defaults fall short. Use
  theme colours (`c="red"`, `color="teal"`), not hard-coded shades.
- **Accessibility checks:** ESLint runs `jsx-a11y`. Playwright runs axe (WCAG 2 A and AA) on the main screens and
  fails on serious or critical findings (`e2e/hardening.spec.ts`). AG Grid's own markup is excluded.
- **Content Security Policy:** set by the API (`SecurityConfiguration`): scripts only from the same origin, no
  inline scripts, no framing. The end-to-end suite fails on any CSP violation. Do not add inline `<script>` or
  third-party origins.
- **Bundle budget:** `npm run budget` (part of `./gradlew :apps:web:check`) fails when the first page's script (entry
  and preloaded chunks) exceeds 330 kB gzip or its stylesheet 50 kB, any chunk exceeds 360 kB, or the whole bundle
  exceeds 1130 kB (raised step by step, each reason in `scripts/check-bundle.mjs`). The help manual is content under
  `/help/` and is not counted.
  Load heavy screens and libraries lazily (`router.tsx`). Raise a budget only in a reviewed change.
