# ADR 0025: Web UI as a single-page application bundled in the API jar

- Status: accepted
- Date: 2026-10-10

## Context
Increments 1–3 built the domain behind about 280 REST operations. Users need screens for these:
- the approval inbox;
- trade capture and the blotter;
- inventory with the criteria builder;
- allocation;
- market data, valuation and risk;
- ETS compliance, the ledger, month end, reports and admin.

The UI has to respect the same roles, four-eyes rules and legal-entity scope as the API. It must not need a
second deployable or cross-origin calls.

## Decision
- **Stack:** a React and TypeScript single-page application in `apps/web` (Gradle project `:apps:web`), built
  with Vite. Components come from Mantine, and AG Grid Community draws the large tables. TanStack Query handles
  server state and React Router the routing.
- **Packaging:** the Vite output goes into the API jar under `static/`, so one image serves both from one origin.
  - There is no CORS configuration.
  - The Vite dev server proxies `/api` during development.
  - `-Petrm.skipWeb` builds the backend alone.
- **Serving** (`SpaConfiguration`):
  - Hashed files under `/assets/` are cached for a year as immutable.
  - Any other GET outside `/api`, `/actuator`, `/v3`, `/swagger-ui` and `/error` that is not a file answers
    with `index.html` (`no-cache`), so deep links survive a reload.
  - Unknown API paths stay 404 problems.
  - Responses are compressed.
- **Headers:** a content security policy (own scripts only, inline styles for Mantine, the identity provider for
  token calls), `Referrer-Policy: strict-origin-when-cross-origin`, and `X-Frame-Options: DENY`.
- **Bootstrap endpoints:**
  - `GET /api/v1/ui-config` (public) returns the sign-in mode, the OIDC settings, the environment label, the
    version and the demo flag.
  - `GET /api/v1/me` returns the user's id, display name, roles and legal entities.
- **Errors:** framework errors that carry a status keep it as an RFC 9457 problem: an unknown path is a 404, a
  wrong method 405, a missing or malformed parameter 400. Before this change they were 500s.
- **Entity scope:** lists without an entity filter are limited to the user's entities (`Actor.entityFilter`;
  trades and lots so far). Single records of another entity are refused. `EntityScopeInterceptor` refuses any
  API request whose `legalEntityId` or `holderEntityId` parameter names an entity outside the user's scope.
- **Out of scope for increment 4:** screens for settlement, payments and registry operations. Other screens link
  to them read-only.

## Consequences
- The backend stays the authority: the UI hides actions a role cannot take, and the API still refuses them.
- The jar build needs Node.js, and CI installs it.
- A user limited to some entities no longer sees other entities' trades and lots in lists. This was a gap in the
  read paths.
- The interceptor only knows parameter names. A read endpoint keyed by another name must check scope in its
  service.
