# ADR 0032: Showcase sign-in with demo accounts and personas

- Status: accepted
- Date: 2026-10-11

## Context
Increment 7 puts the application on a public VPS as a showcase. Visitors must be able to try every role, including
four-eyes paths, without an identity provider to run. The dev profile is not an option: it authenticates nobody and
takes roles from request headers (ADR 0026). The demo seeder still has to create its data through the REST API as
the personas.

## Decision
- **Profile `showcase`** (`ShowcaseSecurityConfiguration`). It cannot run together with `dev` (`ProfileGuard`).
- **Demo accounts** come from the environment:
  - `ETRM_DEMO_PASSWORD` (user `demo`) or `ETRM_DEMO_USERS` (`user:password,…`);
  - a value may be a `{bcrypt}` hash (`ShowcaseSecurityConfiguration.bcrypt`), otherwise it is hashed at start;
  - the start fails without an account, or with a password shorter than 12 characters, equal to the user name or
    in a short list of obvious ones.
- **Session cookie** `ETRM_SESSION` (http-only, SameSite Lax, 8 hours, a new id at sign-in). Sign-in is a form post
  to `/api/v1/auth/login` (204, or a 401 problem); sign-out is `POST /api/v1/auth/logout`.
- **CSRF** with Spring Security's SPA support: the `XSRF-TOKEN` cookie is read by the UI and echoed in
  `X-XSRF-TOKEN` on unsafe methods.
- **Personas.** A signed-in visitor acts as the persona named in `X-Persona` (default `trader`). The server takes the
  roles from its own catalogue (`web/Personas.kt`), with all legal entities; an unknown persona is a 403.
  `X-Actor`, `X-Roles` and `X-Entities` are ignored.
- **Throttle.** Five failed sign-ins per user name or client address within 15 minutes lock that key for the
  window, and the sign-in accepts at most 30 attempts a minute in total; a lockout is a 429 problem with
  `Retry-After`. The client address comes from the proxy (`forward-headers-strategy: native`, which trusts private
  and loopback proxies such as Caddy on the Docker network).
- **Seeder.** The application makes a random 256-bit token at start and keeps it in memory only. The in-process
  `DemoClient` sends it as `X-Etrm-Seed`; with it, the dev headers name the persona, as in development. The token is
  accepted from a loopback peer only, by the socket address, and a wrong or remote token is a 403. Requests carrying
  the token skip CSRF: they hold no cookie.
- **Logging.** The sign-in name goes to the log context (`etrm.login`), so the access log shows who acted as whom.
- **Public paths** are as in ADR 0026 plus `/api/v1/auth/**`. API docs and the Prometheus endpoint need a sign-in.
- **UI:** `ui-config` says `authMode: SHOWCASE` and lists the personas. The UI probes `/api/v1/me`; a 401 shows the
  sign-in page, which explains the personas, the shared data, the nightly reset and that all data is fictitious.
  Inside, the persona menu is as in development, a teal banner marks the demo, and the account menu shows the
  sign-in name and signs out. A 401 later (timeout, reset) returns to the sign-in page with a notice.

## Consequences
- Every visitor can act as any persona, ADMIN included, on shared data; the nightly reset (ADR 0033) limits the
  damage. The login notice says so.
- The audit trail records the persona, not the visitor. Two visitors acting as the same persona count as one person
  for four-eyes checks.
- Until a domain and HTTPS are set, the password travels unencrypted; the deployment guide says so.
- Tests: `ShowcaseSecurityIT` (CSRF, ignored dev headers, personas, lockout, seed token off loopback),
  `ShowcaseUsersTest`, and the Playwright `showcase` project on its own database.
