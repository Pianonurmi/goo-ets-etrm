# ADR 0054: Nullability and JSON type information in module APIs

- Status: accepted
- Date: 2026-10-16

## Context
Module `api` packages are the contracts other modules and the REST layer use, and their types become the OpenAPI
document (ADR 0027) and, through it, the web's generated types. Two questions kept coming back in review: what a
nullable property means, and whether these Kotlin types may carry Jackson annotations.

## Decision
- **Nullable means "may be absent", for a reason the KDoc names** (not yet known, not applicable, not set). A
  property that always has a value is not nullable, even when a default would do. Collections are never nullable:
  empty is empty.
- **The OpenAPI document follows Kotlin**: `KotlinRequiredProperties` marks every non-null constructor parameter
  without a default as required, and computed properties as read-only. The web's generated types are therefore exact,
  and `Schema<'X'>` / `Body<'X'>` carry the same optionality as the Kotlin type.
- **Requests use defaults for optional fields**; a nullable request field means the caller may clear the value.
- **Polymorphic API types keep their Jackson annotations** (`@JsonTypeInfo`, `@JsonSubTypes` on `Criterion`,
  `VintageRule`, `AllocationConstraint`). They are part of the contract, not configuration: springdoc reads them from
  the class to publish the `type` discriminator and its mapping, which the web's criteria builder depends on. Moving
  them to mixins in `platform` JSON (planned in increment 16) would hide them from springdoc and need a custom model
  converter to restore the same schema, splitting one contract across two places. No other Jackson annotation is used
  in `api` packages; serialisation settings stay in `Json.configure`.

## Consequences
- A reader of an `api` type sees its wire shape in one place.
- New polymorphic types follow the same pattern: a sealed type with `@JsonTypeInfo(use = NAME, property = "type")`
  and its subtypes listed, names in lower camel case.
