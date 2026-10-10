# ETRM generic registry REST contract

ETRM's own API contract for registry integration, used by the `GENERIC_REST` mapping. A registry, or integration
middleware in front of a registry, that implements it can be connected without code changes. It is not any
registry's published API. Registry-specific APIs (G-REX, NECS) get their own mapping (see
[registries](registries.md#api-adapters)).

## Security

- When `token-url` is configured, every call carries `Authorization: Bearer <token>`. The token comes from an
  OAuth 2 token endpoint:
  - `client_credentials` with `client_id` and the secret from `credential-env`; or
  - `refresh_token` with the refresh token from `refresh-token-env`, obtained once by an interactive PKCE login.
- Tokens are cached until 30 s before `expires_in`. A `401` renews the token once and repeats the call; a request
  rejected with `401` was not processed.
- Every call carries `Idempotency-Key: <instructionKey>`. The server must treat a repeated key as the same
  instruction and answer with its current state.

## Submit an instruction

`POST /instructions`

```json
{
  "instructionKey": "plan-01J…-line-01J…",
  "type": "TRANSFER | CANCELLATION | ALLOWANCE_TRANSFER | SURRENDER",
  "registry": "FINGRID",
  "fromAccount": "FI-ACC-1",
  "toRegistry": "FINGRID", "toAccount": "FI-CP-9",
  "issuanceRef": "FI-2026-0030", "certificateStart": "40000", "certificateEnd": "40499",
  "product": null, "quantity": 500, "unit": "MWH",
  "complianceYear": null,
  "cancellation": { "purpose": "DISCLOSURE", "beneficiaryName": "…", "beneficiaryId": "…",
                    "consumptionCountry": "FI", "consumptionStart": "2026-01-01", "consumptionEnd": "2027-01-01" }
}
```

| Answer | Meaning | ETRM outcome |
|---|---|---|
| `2xx` `{"id":"…","status":"COMPLETED"}` | executed | CONFIRMED |
| `2xx` `{"id":"…","status":"ACCEPTED"}` (or any other status) | accepted, executes later | SENT; confirmed by a status query or a statement |
| `2xx` `{"status":"REJECTED","reason":"…"}` | refused after acceptance | FAILED |
| `4xx` `{"error":"…","message":"…"}` | refused, nothing happened | FAILED with the message |
| `408`, `429`, `503` | not taken | retried with backoff |
| `500`, `502`, `504`, timeout | may have been processed | UNKNOWN, resolved by a status query, never resent |
| connection refused | not reached | retried |

## Instruction status

`GET /instructions/{instructionKey}`
- `200` with `{"id":"…","status":"ACCEPTED|PROCESSING|COMPLETED|REJECTED|CANCELLED","reason":"…"}`.
- `404` when the key is unknown.

Status queries are retried on transport errors and `5xx`. They resolve UNKNOWN instructions and confirm SENT ones
from the recovery job.

## Configuration

```yaml
etrm.registry.adapters.FINGRID:
  enabled: true
  mapping: GENERIC_REST
  base-url: https://registry-gateway.example/api
  token-url: https://login.example/oauth2/token
  client-id: etrm
  credential-env: ETRM_REGISTRY_FINGRID_SECRET   # the secret lives only in the environment
  grant-type: CLIENT_CREDENTIALS                 # or REFRESH_TOKEN with refresh-token-env
  read-timeout: 30s
```

The registry's integration mode must be `API` (a four-eyes change request). Adapters are disabled until configured.
