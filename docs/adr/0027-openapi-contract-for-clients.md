# ADR 0027: The OpenAPI document as a checked contract for generated clients

- Status: accepted
- Date: 2026-10-10

## Context
The web UI calls about 280 operations. Hand-written TypeScript types would drift from the Kotlin views. A
generated client needs a document that is stable and precise. Before this change, the document was neither:
- operation ids clashed (`approve`, `cancel` and `run` in several controllers);
- nothing was marked required;
- polymorphic types such as `Criterion` lost their discriminator values;
- error responses were undocumented.

## Decision
- **Operation ids** are `<controller>_<method>`, for example `trade_approve` or `etsCompliance_register`
  (`OpenApiConfiguration`). Handler names are unique within a controller.
- **Required properties** (`KotlinRequiredProperties`):
  - a constructor property that is neither nullable nor defaulted is `required`;
  - a computed property (serialised, never read) is `readOnly`, and required when non-null. The client is generated
    with read/write markers, so response types include it and request types do not;
  - nullability travels as `type: [x, "null"]`.
- **Polymorphic types** (`PolymorphicSchemas`): every `@JsonTypeInfo`/`@JsonSubTypes` root (`Criterion`,
  `VintageRule`, `AllocationConstraint`) becomes a `oneOf` with a discriminator mapping. Each subtype carries a
  literal `type` enum, so clients get a discriminated union.
- **Errors:** every operation documents a `default` response of `application/problem+json` with the shared
  `Problem` schema (`type`, `title`, `status`, `detail`, `violations`).
- **Media types and servers:** responses and requests are `application/json`. The server is relative (`/`).
- **Snapshot:** the document is committed as `apps/api/openapi/etrm-api.json`, with keys sorted.
  - `OpenApiSnapshotIT` fails when the running API differs, and checks that operation ids are unique and carry
    no numeric suffixes.
  - `./gradlew :apps:api:updateOpenApiSnapshot` rewrites the file.
- **Schema names:** schemas are named after simple class names. `SchemaNamesTest` fails when two classes behind
  one schema name have different fields. This found and fixed the clash of the inventory and workflow
  `CancelRequest`s.
- **Client generation:** the web client (`openapi-typescript`, `openapi-fetch`) is generated from the committed
  file. It is not generated from a running server, so the UI build needs no backend.

## Consequences
- Every API change shows up as a snapshot diff in review. Regenerate the snapshot; do not hand-merge it.
- Renaming a controller method renames its operation id and breaks UI code that uses it at compile time. That is
  intended.
