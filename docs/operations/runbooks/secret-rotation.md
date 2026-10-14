# Secret and certificate rotation

Rotate one credential at a time and verify before the next.

| Credential | Where | How |
|---|---|---|
| Runtime database role | secret `etrm-db-runtime` (CloudNativePG managed role `etrm_app`) | Change the secret; the operator updates the role. Restart the deployment. |
| Schema owner | secret `etrm-db-app` | As above; the owner is used at start (migrations) and by partition maintenance. |
| OIDC signing keys | identity provider | Rotate there with overlap; the API reads the JWKS and needs no restart. |
| Registry API credentials | the registry adapters' `existingSecret` | Change the secret, restart; an instruction in flight may end `UNKNOWN`, see [registry instructions](registry-instructions.md). |
| Chain-head digest key | `integrity.signingKeySecret` | Change it on a known date and record that date with the digests; old digests verify with the old key. |
| TLS certificates | ingress (cert-manager or the ingress controller) | Renewed by the controller; check the expiry date. |
| Showcase passwords | `ETRM_DEMO_PASSWORD`, `ETRM_DEMO_USERS` | Change in `ops/demo/.env`, restart the stack. |

**Verify** after each: the application starts, sign-in works, and a registry instruction or GL export goes through.
