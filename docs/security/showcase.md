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
| Strangers reaching an invite-only demo | Behind a Cloudflare Tunnel (`docker-compose.tunnel.yml`) the server publishes no port; Cloudflare Access admits listed e-mail addresses only, and the connector refuses requests without a valid Access token |
| One visitor locking everyone out behind a proxy | Behind the tunnel, Caddy takes the visitor's address from `CF-Connecting-IP`, trusted only from the connector's fixed network, and passes exactly one address to the application |
| Session cookie over plain HTTP | Behind the tunnel the cookie is always `Secure` and Caddy sends HSTS; an expired Access session makes the page reload instead of following a cross-origin redirect |
| A compromised container widening its reach | No new privileges in any container, all capabilities dropped (Caddy keeps only binding port 80), the application read-only as an unprivileged user, process and memory limits, rotated logs |

## Accepted risks

- **Shared sandbox.** Every visitor can act as any persona, ADMIN included, and change shared data until the
  nightly reset (ADR 0033). The ADMIN persona can also reset at once, but not within ten minutes of a start.
- **Audit identity.** The audit trail records the persona, not the visitor; the sign-in name is in the logs only.
  Two visitors acting as one persona count as one person for four eyes.
- **Write abuse.** There is no write throttle; the nightly reset and the container memory limit contain it.
- **Plain HTTP.** Until a domain is configured the password travels unencrypted.
- **One shared sign-in behind the tunnel.** Access knows who came in (its log has the e-mail address); Provena sees
  only `demo`. Removing someone means removing them from the Access policy, revoking their session and changing the
  demo password.
