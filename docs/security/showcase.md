# Security of the public showcase

The showcase (`showcase` profile, ADR 0032) puts the application on the internet with fictitious data. This page
lists what protects it and what is accepted.

## What protects it

| Threat | Control |
|---|---|
| Anyone reading or changing data | Every API call except `ui-config`, health and `/api/v1/auth/**` needs a signed-in session |
| Guessing the demo password | 12 characters at least, refused at start when obvious; 5 failures per user name or address lock that key for 15 minutes; 30 attempts a minute in total |
| Cross-site request forgery | CSRF token (`XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header) on every unsafe call; SameSite Lax session cookie |
| Session theft by script | Session cookie is http-only; the content security policy allows no inline script |
| Session fixation | A new session id at sign-in |
| Acting with roles of one's own choosing | Roles come from the server's persona catalogue; `X-Actor` and `X-Roles` are ignored; an unknown persona is a 403 |
| Abusing the seeder's access | The seed token is random per start, kept in memory, never logged or returned, and accepted from loopback only |
| Running the unauthenticated dev profile publicly | `ProfileGuard` refuses `dev` with `showcase`, and `dev` on a non-loopback address |
| Indexing and API discovery | API docs need a sign-in; the proxy adds `noindex` and hides the actuator (M6) |

## Accepted risks

- **Shared sandbox.** Every visitor can act as any persona, ADMIN included, and change shared data until the
  nightly reset.
- **Audit identity.** The audit trail records the persona, not the visitor; the sign-in name is in the logs only.
  Two visitors acting as one persona count as one person for four eyes.
- **Write abuse.** There is no write throttle; the nightly reset and the container memory limit contain it.
- **Plain HTTP.** Until a domain is configured the password travels unencrypted.
