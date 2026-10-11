# Testing the web UI

| Level | Tool | What it covers | Where |
|---|---|---|---|
| Pure logic | Vitest | Criteria tree edits, trade form mapping, plan grouping, ladders, limit scopes, deadline calendar, manual journal lines, GL balance parsing, report parameters, history diffs, palette matching, contrast | `src/**/model/*.test.ts`, `src/app/*.test.ts` |
| Components | Vitest, Testing Library, msw | The shell with a session, the criteria builder against a mocked API | `src/**/*.test.tsx` |
| Journeys | Playwright against the jar | Each milestone's main journey on demo data, with two personas where four eyes apply | `e2e/*.spec.ts` |
| Accessibility and CSP | Playwright with axe | Serious or critical axe findings on the main screens; CSP violations; the CSP header | `e2e/hardening.spec.ts` |
| Contract | JUnit (`OpenApiSnapshotIT`) | The API matches the committed OpenAPI snapshot the UI is typed from | `apps/api` |

## Running

```bash
cd apps/web
npm test                                   # Vitest
npm run test:coverage                      # with coverage
../../gradlew -p ../.. :apps:api:bootJar   # the jar the journeys run against
npm run e2e                                # Playwright: starts the jar with dev,demo on port 18080
```

The journeys need an empty database the first time, so the demo data is created (`etrm_e2e` by default, see
`E2E_DB_URL`). They change data as they go, for example new trades, limits and taxonomy nodes. Each spec creates what it
asserts on or reads demo data that no other spec changes. Recreate the database to start again.

## Writing journeys

- Act as a persona with `actAs(page, 'middle')` before the first navigation. Switch with `switchPersona` to show
  four eyes.
- Prefer roles and labels (`getByRole('gridcell', { name })`, `getByLabel`) and add `data-testid` only for elements
  without an accessible name.
- Mantine selects open with a click on the input and a click on the `option`.
- Wait for the state the user waits for, such as a narrowed grid or a status badge, before acting. Do not click rows
  while a search is still replacing them.
