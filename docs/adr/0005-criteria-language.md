# ADR 0005: A typed criteria language for GoO delivery terms

- Status: accepted
- Date: 2026-10-10

## Context
A GoO sale is agreed on attributes (region, technology, vintage, labels, support, device age), not on certificate
numbers. The same terms must drive inventory search (SQL), allocation (in memory over lot classes), bucket mapping and
human-readable confirmations, and must evaluate identically years later.

## Decision
`Criterion` is a sealed tree (equals, in-set, ranges, taxonomy descendant, labels, region, device age at delivery,
vintage windows, all/any/not). It is stored as a versioned JSON envelope with upcasters, bound for a delivery date into
a `BoundCriterion`, and then either compiled to a jOOQ `Condition` over columns the inventory module exposes or
evaluated against a `CriteriaSubject`. NULL semantics are fixed (missing attribute never matches; `Not` compiles to
`NOT COALESCE(x, false)`). A property-based test proves SQL and in-memory evaluation select the same rows.

## Consequences
- New predicates need a leaf type, a binder case, an evaluator case, a compiler case and a describer case; the exhaustive `when` keeps them in step.
- Criteria are content-addressed (hash) so identical terms share one row.
