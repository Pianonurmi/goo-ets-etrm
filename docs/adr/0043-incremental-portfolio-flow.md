# ADR 0043: Incremental preparation of the portfolio flow

- Status: accepted
- Date: 2026-10-13
- Amends: ADR 0036

## Context
Available to sell, the product hierarchy and the GoO limits read one prepared flow per entity (ADR 0036). It is
checked against a stamp of aggregates on every read, and any change to the entity's lots or open obligations made it
stale. Preparing it again was a full problem build.

- **Too slow for a busy book.** At design size (100 000 lots, 5 000 obligations) a full build took about 22 s on a
  uniform book. A realistic one took about 28 s: expiry dates spread over a year and several target registries
  give about 2 200 demand groups and 1 000 supply atoms instead of 21 × 12.
- **Who waited:**
  - Every pre-trade check after a change read "not checked".
  - The available-to-sell table and the portfolio view blocked on the rebuild.
  - Risk's GoO measures, which run inside event transactions, blocked on it too.
- **Increment 10 M0** brought the full build to about 3.5 s:
  - eligibility rows share their parts;
  - the scarcity index is left to the solvers;
  - atoms come from partition refinement;
  - concurrent readers wait for one build.

  That is still seconds after every change.

## Decision
### Profiles
- **Definition.** A held class's profile is three things:
  - one bit per distinct bound criterion in the rows (matched or not);
  - its registry;
  - its expiry rank among the rows' delivery dates.
- **Two classes with the same profile are in exactly the same rows.** A row is the conjunction of "matches the
  bound", "reaches the target registry" and "expires after the delivery date".
- **Compression refines profiles instead of classes.** Supply groups are (tier, bucket and year, the profile's
  block), and the eligible pairs are never walked. The resulting supply groups and their order are the same as
  without profiles.
- **Future classes** each have a profile of their own.

### The patch
- **Scope.** The held tier, and amendment variants (obligations left out) derived from it. The supply tiers beyond
  held (ADR 0038) keep the full build: they recompute their future supply on every read anyway.
- **What the cache keeps.** Each held entry keeps an index next to the published flow:
  - live lots by id and version;
  - classes by class key;
  - the eligibility parts (matches per bound criterion, expiry days, registry masks, rows by key);
  - the open obligations with their versions.

  Only the build for that key, which runs once at a time (single flight), changes the index.
- **Finding the changes.** A stale read diffs the entity's live lots by `(id, version)` with one narrow query, and
  fetches only the lots that differ. It is exact even when transactions commit out of order, which a journal
  position would not be. The open obligations (thousands of rows) are read and diffed by version and by the
  quantity in flight.
- **Applying them:**
  - A changed lot leaves its class and joins the class of its key.
  - An emptied class stays as a tombstone with no quantity, so indexes hold.
  - A new class key is appended. Its bits are set in every cached match and row, which costs one evaluation per
    distinct bound criterion.
  - A new eligibility key evaluates its criterion once, against the cached matches when the bound criterion is known.
- **Publishing.**
  - A new problem is published, with copied rows, fresh profiles and a solved flow.
  - The previous entry is never mutated, so readers keep it until the new one replaces it.
  - The entry's stamp is the one read before the diff. A change committed during the patch therefore leaves the
    entry stale, and the next read patches again.
- **Falling back to a full build:**
  - a new day;
  - a change to the bucket catalogue;
  - tombstones over a fifth of the classes;
  - more changed lots than a tenth of the live ones;
  - any failure of the patch.

## Consequences
- **Measured at design size** (`PortfolioPrepareBenchmarkTest`, realistic book):
  - a full build takes about 3 s (problem 2 s, flow 0.7 s);
  - a patch takes about 0.3 s, for one changed lot or a thousand.
- **Pre-trade checks.** After a change, `ifReady` patches at once instead of answering "not checked". Only an entity
  never prepared since start-up, or one past a full-build condition, still builds in the background.
- Correctness rests on a property test: random sequences of lot and obligation changes, patched against a full
  build, comparing per-obligation coverage, residuals, headroom and the table.
- Transfer routes are still not in the stamp. A route change is seen at the next full build (a new day at the
  latest), as before.
- Memory per entry grows by the index: one map of lot versions and the eligibility parts, small next to the lots the
  classes already hold.
