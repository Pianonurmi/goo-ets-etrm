# Registry integration

Pattern (ADR 0009): instruction → execution → confirmation.

| Registry | Type | Increment 1 mode | Notes |
|---|---|---|---|
| FINGRID (Finextra), CESAR, NECS, ENERGINET, ELERING, CERTIQ, HKNR | GoO, AIB hub members | FILE: instruction packs in `etrm.registry.outbox-dir`, CSV statement import | Grexel-based registries can move to API gateways in increment 2 |
| UNION_REGISTRY | EU ETS | MANUAL: operator executes in the registry UI (registry four-eyes) and confirms in the application | Statement import for holdings reconciliation to follow |
| SIMULATOR | GoO | SIMULATOR: confirms synchronously | Tests and demos |

Routes: within one registry free and next day; between two hub members via the AIB hub at the configured fee and
expected days; otherwise infeasible. Overrides in `etrm_registry.transfer_route`.

Statement CSV (generic): `issuance_ref,cert_no_start,cert_no_end,device_gsrn,production_start,production_end[,extra]`;
`POST /api/v1/registry/statements/csv?registryAccountId=...&statementDate=...` imports and reconciles, raising
`MISSING_INTERNALLY`, `MISSING_IN_REGISTRY` and `ATTRIBUTE_MISMATCH` breaks.

Counterparty accounts on trades use the form `REGISTRY_CODE:account-number` so the target registry is known.
