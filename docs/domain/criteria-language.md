# Criteria language

Delivery terms are a `Criterion` tree serialised as `{"v": 1, "criterion": {...}}`.

```json
{"v": 1, "criterion": {"type": "all", "of": [
  {"type": "countryInRegion", "region": "NORDIC"},
  {"type": "descendantOf", "attribute": "ENERGY_SOURCE", "code": "HYDRO"},
  {"type": "vintage", "rule": {"type": "productionYear", "year": 2026}},
  {"type": "not", "of": {"type": "labelsAny", "labels": ["BRA_MILJOVAL"]}},
  {"type": "deviceAgeAtDelivery", "maxYears": 10}
]}}
```

| Type | Meaning | Bound form |
|---|---|---|
| `equals`, `in` | scalar equality / membership (NULL never matches) | same |
| `numberRange`, `dateRange` | inclusive numeric bounds; dates `[from, toExclusive)` | same |
| `descendantOf` | taxonomy code is the given code or below it | ltree path (`path <@ ancestor`) |
| `labelsAny`, `labelsAll` | label set overlap / containment | same |
| `countryInRegion` | device country in a region set | `in` over the region's countries |
| `deviceAgeAtDelivery` | commissioned at most N (and at least M) years before delivery | date range on commissioning date |
| `vintage` | production period within a window: `productionYear`, `sameCalendarYearAsDelivery`, `monthsBeforeDelivery`, `explicit` | `production_period <@ daterange` |
| `all`, `any`, `not`, `matchAll` | composition | same |

Static specificity (weights in `etrm_product.specificity_weight`) orders criteria from most to least constrained;
the allocation engine uses dynamic constrainedness (eligible supply divided by demand) first.

Equivalence of the SQL compiler and the in-memory evaluator is proven by `CriteriaSqlEquivalenceIT` over random
criteria trees and random lot populations including NULLs.
