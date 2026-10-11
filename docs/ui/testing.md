# Testing the web UI

| Level | Tool | What it covers | Where |
|---|---|---|---|
| Pure logic | Vitest | Criteria tree edits, trade form mapping, plan grouping, ladders, limit scopes, deadline calendar, manual journal lines, GL balance parsing, report parameters, history diffs, palette matching, contrast | `src/**/model/*.test.ts`, `src/app/*.test.ts` |
| Components | Vitest, Testing Library, msw | The shell with a session, the criteria builder against a mocked API | `src/**/*.test.tsx` |
| Journeys | Playwright against the jar | Each milestone's main journey on demo data, with two personas where four eyes apply | `e2e/*.spec.ts` |
| Public demo | Playwright `showcase` project | Sign-in page with axe, persona switching under a session, four eyes, sign-out, lockout | `e2e/showcase.spec.ts` |
| Accessibility and CSP | Playwright with axe | Serious or critical axe findings on the main screens; CSP violations; the CSP header | `e2e/hardening.spec.ts` |
| Contract | JUnit (`OpenApiSnapshotIT`) | The API matches the committed OpenAPI snapshot the UI is typed from | `apps/api` |

## Running

```bash
cd apps/web
npm test                                   # Vitest
npm run test:coverage                      # with coverage
../../gradlew -p ../.. :apps:api:bootJar   # the jar the journeys run against
npm run e2e                                # Playwright: the jar with dev,demo on 18080 and with showcase,demo on 18081
npx playwright test --project=showcase     # only the public demo journeys (e2e/showcase.spec.ts)
```

The journeys need an empty database the first time, so the demo data is created (`etrm_e2e` by default, see
`E2E_DB_URL`). They change data as they go, for example new trades, limits and taxonomy nodes. Each spec creates what it
asserts on or reads demo data that no other spec changes. Recreate the database to start again. The `showcase` project
signs in as `demo` (password `ETRM_DEMO_PASSWORD`, a test default otherwise) on its own database
(`etrm_showcase_e2e`, see `E2E_SHOWCASE_DB_URL`); `signIn(page)` in `e2e/fixtures.ts` does it through the login page.

## Writing journeys

- Act as a persona with `actAs(page, 'middle')` before the first navigation. Switch with `switchPersona` to show
  four eyes.
- Prefer roles and labels (`getByRole('gridcell', { name })`, `getByLabel`) and add `data-testid` only for elements
  without an accessible name.
- Mantine selects open with a click on the input and a click on the `option`.
- Wait for the state the user waits for, such as a narrowed grid or a status badge, before acting. Do not click rows
  while a search is still replacing them.
- Tours point at `data-testid` selectors; `help.spec.ts` runs every tour to its end and fails when a step loses its
  anchor, so keep a test id a tour uses, or move the step with the screen.

## Documentation screenshots

The pictures in `docs/images` (the manual, the README and the deck) come from `e2e/docs/screenshots.spec.ts`, run by
its own Playwright config: the jar in the `showcase,demo` profiles on database `etrm_docs`, signed in as a visitor,
1440 × 900, light mode plus one dark shot.

```bash
cd apps/web && E2E_DOCS_DB_URL=jdbc:postgresql://localhost:5432/etrm_docs npm run docs:screenshots
npm run check:help   # every image at most 300 kB, all of them at most 12 MB
```

Look at every changed image before committing it. The **Documentation screenshots** workflow
(`.github/workflows/docs-screenshots.yml`, manual and weekly) takes them in CI and uploads them as an artifact; it
does not commit them.
