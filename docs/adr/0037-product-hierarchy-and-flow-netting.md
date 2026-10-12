# ADR 0037: A product hierarchy from proven containment, and positions netted through the flow

- Status: accepted (M4 of increment 8)
- Date: 2026-10-12
- Amends: ADR 0016 (bucket mapping by criteria implication)

## Context
Product buckets are flat. Each certificate counts in every bucket it satisfies, and each trade leg or obligation
gets the most specific bucket that provably contains it (ADR 0016). A desk thinks of them as a tree: Finnish hydro
is Nordic hydro is European hydro is European renewable is any GoO.

Netting is asymmetric along that tree. A specific certificate can deliver a generic sale, but not the reverse. A
position that adds the certificates of a bucket and subtracts its obligations therefore misleads. A book with
1 000 MWh of Nordic wind and a 600 MWh Nordic hydro sale looks 400 MWh long in "Nordic renewable", yet it is 600 MWh
short there: none of its certificates can deliver that sale.

## Decision
- **The hierarchy** (`ProductBucketQuery.hierarchy`, `GET /api/v1/products/buckets/hierarchy`):
  - Every active bucket is bound for a date and compared with every other with the implication proof of ADR 0016.
  - Buckets proven equivalent become one node; the others are its *aliases*.
  - A node's parents are its smallest proven containers: the transitive reduction (Hasse diagram) of containment.
    A node can have several parents: Nordic hydro is under European hydro and under Nordic renewable, and neither
    contains the other.
  - The proof is incomplete, so "not proven" places a bucket higher, never under a wrong parent. Labelled buckets
    (EKOENERGY) prove nothing about region and sit under ANY_GOO.
  - With the default catalogue: ANY_GOO → EU_RENEWABLE → EU_HYDRO / NORDIC_RENEWABLE → NORDIC_HYDRO → FI_HYDRO
    (also under FI_RENEWABLE), and likewise for wind and solar.
- **The portfolio view** (`PortfolioQuery`, `GET /api/v1/allocation/portfolio`): for each node, on the entity's
  prepared held flow (ADR 0036):
  - **long:** held certificates satisfying the bucket;
  - **short:** open obligations whose criteria are provably contained in the bucket, which only its certificates
    can serve;
  - **covered within:** the max-flow between the two, inside the bucket;
  - **short after netting** = short − covered within: has to be bought or issued;
  - **naive net** = long − short: shown for comparison, because it is what a plain position shows;
  - **free:** the bucket's certificates unused by the entity-wide flow;
  - **available to sell:** a headroom trial for a new sale of exactly the bucket.
- **Values in the view (management view only):**
  - the bucket's mark for the current vintage;
  - the cheapest to deliver: the lowest value among its free certificates, each valued at the best price it
    qualifies for (ADR 0035);
  - the give-away per MWh = cheapest to deliver − mark: the premium handed over if the bucket were sold now.
  - Official valuation stays on bucket curves.
- **Prepared flows are shared:** the per-entity cache of ADR 0036 (`PortfolioFlowCache`) serves available to sell
  and the portfolio view alike.
- **UI:** Positions → GoO → Products: the hierarchy as an indented table. A node with several parents appears
  under each, marked "also under". The admin buckets screen shows each bucket's containers.

## Consequences
- **Gaps are visible:** a book's real gaps show where they are, by product, instead of being netted away by a
  broader bucket's surplus.
- **Containment is computed, not entered:** a new bucket finds its place in the tree as soon as it is created.
  Nothing has to be maintained by hand, and a bucket the proof cannot place sits high in the tree rather than
  under a wrong parent.
- **Figures overlap:**
  - A node's figures include its children's, so they do not add up across siblings that overlap.
  - Short after netting is per node; an obligation counts in every node that contains it.
- **Not yet built:**
  - The figures were for held certificates only; purchases and expected issuance join them by tier (ADR 0038).
  - They cover all vintages together; the available-to-sell table (ADR 0036) splits by vintage.
