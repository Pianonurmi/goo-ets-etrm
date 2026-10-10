# ADR 0016: Trades map to product buckets by proved criteria implication

- Status: accepted
- Date: 2026-10-10

## Context
Positions, marks and (from increment 3) valuation curves are keyed by product bucket. Lots find their bucket by
evaluating each bucket's criteria on the lot's attributes. Trades are agreed on criteria, not on certificates. Until
now a trade leg got a bucket only when its stored criteria were exactly a bucket's criteria, so almost every
negotiated delivery criterion ("Finnish reservoir hydro, 2026") had no bucket and could not be valued.

Two easy alternatives are wrong. Picking the bucket whose criteria overlap the trade's would value a trade on a curve
for certificates it may not accept. Evaluating the bucket against a sample lot proves nothing.

## Decision
- A trade leg maps to a bucket in this order:
  - the bucket whose stored criteria are the leg's (`EXACT`);
  - otherwise the most specific active bucket whose criteria provably contain the leg's (`IMPLIED`);
  - otherwise no bucket.

  Ties are broken by sort order, then code, the same order lots use.
- The proof is `CriteriaImplication.implies(narrow, broad)` on bound criteria. Bucket and leg are bound for the leg's
  first delivery date, which resolves regions, taxonomy codes and delivery-relative vintages. The procedure is
  sound and incomplete:
  - it only answers `true` with a proof built from atomic containments (set inclusion, range containment, taxonomy
    descendants, label sets, vintage windows), conjunction and disjunction;
  - `Not` is matched only structurally, because contraposition is invalid when attributes can be missing;
  - a criterion it cannot prove falls back to a broader bucket (ultimately `ANY_GOO`), never to a wrong narrower one.
- `trade_leg.bucket_match` records how the bucket was found.
- A property test checks soundness: for random criterion pairs where an implication is claimed, every random
  certificate matching the narrow criterion matches the broad one.

## Consequences
- Most negotiated GoO trades now have a bucket and therefore a curve and a position key. Trades with criteria the
  procedure cannot reason about land in a broad bucket. That is conservative for valuation, and a reason to extend
  the procedure rather than to guess.
- The bucket is fixed at capture or amendment. A later catalogue change does not move existing trades.
