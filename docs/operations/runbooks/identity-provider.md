# Identity provider outage

**Signal.** Users cannot sign in; every API call answers 401; the log shows JWKS or issuer errors.

**Confirm.** `curl -s $ETRM_OIDC_ISSUER/.well-known/openid-configuration` from a pod; the token's `iss` and `aud`
against `ETRM_OIDC_ISSUER` and `ETRM_OIDC_AUDIENCE`.

**Do.** Restore the identity provider; the API caches the signing keys and picks up rotated keys from the JWKS. When
it rotated keys or changed the issuer URL, set the new issuer and restart. Scheduled jobs keep running (they need no
token).

**Verify.** Sign-in works and API calls answer 200.
