# ADR 0026: Browser sign-in with Authorization Code and PKCE; personas in development

- Status: accepted (the Keycloak realm import was not run against a Keycloak server in this environment)
- Date: 2026-10-10

## Context
The API is a stateless OIDC resource server:
- roles come from the `roles` claim;
- legal-entity scope comes from `legal_entities`;
- the display name comes from `name`.

The dev and test profiles take the actor from the `X-Actor`, `X-Roles` and `X-Entities` headers. The browser
needs a way to obtain tokens without a client secret. Developers need to switch users quickly to exercise four
eyes.

## Decision
- **Production:** the UI signs in with Authorization Code and PKCE (S256), using `oidc-client-ts`, against the
  public client `etrm-web`.
  - Tokens stay in memory only, never in browser storage or cookies (amended in increment 14, see below), and renew
    silently.
  - The API is called with `Authorization: Bearer`.
  - Logout ends the identity provider session.
- **Public configuration:** `GET /api/v1/ui-config` is the only public API path. It carries `etrm.ui.*`: the
  authority (`ETRM_OIDC_BROWSER_ISSUER`, by default the API's issuer), the client id and the scope.
- **Public shell:** the UI shell and assets are public, since they hold no data. Every other `/api` call needs a
  valid token; the dev headers are ignored there.
- **Dev and test:** `etrm.ui.auth-mode: DEV_HEADERS`. The UI offers an "act as" switcher with personas (trader,
  middle office, back office, accountant, risk, auditor, admin), sends the dev headers, and shows a permanent
  environment banner.
- **Keycloak realm** (`ops/keycloak/etrm-realm.json`):
  - the client `etrm-web` (public, standard flow only, PKCE S256, redirect and web origins for ports 8080 and
    5173);
  - mappers for `roles`, `legal_entities` (a multivalued user attribute) and the full name;
  - the user profile allows unmanaged attributes, so `legal_entities` can be set per user;
  - demo users get `legal_entities: ["*"]`; there is a second middle-office user, plus risk and auditor users.
- **One issuer:** Keycloak runs with `KC_HOSTNAME=http://localhost:8081`, so browser tokens and the API agree on
  the issuer. The API container fetches keys over the compose network
  (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI`).

## Amendment (increment 14, review findings W1 and W2)
- **Tokens in memory only.** Session storage held the access and refresh tokens, readable by any script that ran
  in the page; the content security policy was the only barrier, and Mantine needs `style-src 'unsafe-inline'`.
  The user store is now in memory. A reload signs in again with a silent request in a hidden frame
  (`prompt=none`) while the identity provider's session lasts, and falls back to the full redirect otherwise.
  A backend-for-frontend with a cookie session was considered and not chosen: it would make the API stateful and
  add CSRF handling to every call, for the same protection against token theft that memory storage gives here.
- **Silent renewal works.** The identity provider redirects the hidden frame to `/auth/silent`, which completes the
  renewal (`signinSilentCallback`) without starting the application. Every page is served with
  `frame-ancestors 'none'` and `X-Frame-Options: DENY` except this one, which the application's own pages may frame
  (`'self'`, `SAMEORIGIN`). Before, the frame was refused, and renewal worked only because the identity provider
  also issued a refresh token. With a refresh token the library still uses it; without one the frame renews.
- When renewal fails and the token has expired, the next call signs in again by redirect, once however many calls
  failed together.
- Browsers that block third-party cookies may refuse the provider's session in the frame; the fallback is the
  redirect, which returns to the same page.

## Consequences
- The content security policy allows the identity provider origin for token calls and the silent-renew frame.
- A real identity provider must issue `roles` and `legal_entities`. A missing `legal_entities` claim grants no
  entity, and `*` must be granted explicitly.
- The realm file follows the Keycloak 26 export format. If an import complains, adjust the user-profile component
  first.
