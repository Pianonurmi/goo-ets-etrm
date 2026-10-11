# Web UI

React and TypeScript single-page application in `apps/web`, served by the API jar from the same origin (ADR 0025).
Sign-in uses OIDC with PKCE in production and dev personas in the `dev` and `test` profiles (ADR 0026). The API
client is generated from the committed OpenAPI document (ADR 0027). Actions are offered by role through a table
keyed by operation id (ADR 0028).

## Run it

```bash
# Backend with dev personas (PostgreSQL as in the main README), then the Vite dev server with hot reload:
SPRING_PROFILES_ACTIVE=dev,demo ./gradlew :apps:api:bootRun
cd apps/web && npm ci && npm run gen:api && npm run dev     # http://localhost:5173, proxies /api to :8080

# Or the bundled build: the jar serves the UI at http://localhost:8080/
./gradlew :apps:api:bootJar && java -jar apps/api/build/libs/api.jar --spring.profiles.active=dev,demo
```

With the `demo` profile as well (`dev,demo`), the first start fills an empty database with demo data through the
REST API (`DemoDataSeeder`):
- the legal entities `DEMO-FI` and `DEMO-SE` with books, fictitious counterparties, simulator accounts and devices;
- certificates across vintages, the oldest expiring within weeks;
- confirmed GoO and EUA forwards, and two trades waiting in the middle office inbox;
- signed-off curves (one more waiting for `risk2`), official valuations and last month's period end;
- for `DEMO-FI`: active credit, tenor and compliance-short limits (a stop loss waiting for `risk2`), a guarantee
  from one counterparty, and an EU ETS installation with free allocation, allowances and this year's estimates;
- for `DEMO-FI` operations: deliveries this week, one invoice paid and one waiting for `controller`, a purchase
  invoice and a netting draft with Nordvind, a manual transfer waiting for a second person, a registry statement
  with a break, and a counterparty confirmation that does not match ours.

It does nothing when `DEMO-FI` exists. Drop the database to start over.

`ETRM_API_URL` points the Vite proxy elsewhere. `./gradlew build -Petrm.skipWeb` builds the backend without the UI.

## Checks

| Command | What |
|---|---|
| `npm run gen:api` | TypeScript types from `apps/api/openapi/etrm-api.json` into `src/shared/api/schema.d.ts` (git-ignored) |
| `npm run typecheck` | `tsc` in strict mode |
| `npm run lint` / `npm run format:check` | ESLint (type-checked, React hooks, accessibility) and Prettier |
| `npm test` | Vitest with Testing Library and msw (`npm run test:coverage` for coverage) |
| `npm run e2e` | Playwright journeys against the jar (`../api/build/libs/api.jar`, `dev,demo` profiles, database `etrm_e2e`) |

`./gradlew :apps:web:check` runs the first four. `./gradlew build` runs them and puts the bundle into the jar.

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

Screens, their operations and roles: [screens.md](screens.md).
Tests at every level: [testing.md](testing.md).

## Working in the UI

- **Command palette:** `Ctrl K` (`⌘ K`) or `/` opens a search over the screens the user may see. From two characters
  on, it also finds trades by number, external reference or UTI in the selected legal entity.
- **Filters in the URL:** lists keep their filters and tabs in the query string, so a view can be bookmarked or
  shared.
- **Legal entity:** the header selector scopes every screen. Its choice is kept per browser.


## Conventions

- **API calls:** go through `$api` (TanStack Query) or `api` (openapi-fetch); paths and bodies are typed from the
  document. A failed call throws `ProblemError` with the server's problem.
  - Queries render their error with `QueryState`.
  - Mutations notify, unless they set `meta: { notify: false }` and map violations onto form fields with
    `applyProblemToForm`.
- **Amounts:** the server computes every amount. The UI formats numbers (`shared/format`) and sends decimals back as
  strings (`decimal()`), so nothing is rounded in the browser.
- **Permissions:** actions are shown or hidden with `<Can op="trade_approve">` and `useCan()`, keyed by operation
  id (`shared/auth/permissions.ts`). The API checks again.
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
- **Bundle budget:** `npm run budget` (part of `./gradlew :apps:web:check`) fails when the first page (entry,
  preloaded chunks and CSS) exceeds 330 kB gzip, any chunk exceeds 360 kB, or the whole bundle exceeds 1000 kB.
  Load heavy screens and libraries lazily (`router.tsx`). Raise a budget only in a reviewed change.
