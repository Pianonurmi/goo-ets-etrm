# ADR 0007: GoO position as coverage computed by max-flow

- Status: accepted
- Date: 2026-10-10

## Context
A Nordic hydro lot can serve a "Nordic" sale or a "hydro" sale, not both. Summing inventory and obligations per bucket
double-counts; a trader needs to know which obligations can actually be covered and which compete.

## Decision
Coverage is a maximum flow from lot classes (supply) through eligibility edges to obligations (demand). Obligations
are attached one priority tier at a time and the flow is augmented after each tier, so a higher priority tier never
loses supply to a lower one while the total stays a global maximum. Unreachable obligations in the residual graph form shortage groups with the exhausted classes they share.
Residual supply per bucket is surplus. The greedy solver's plan is a lower bound; coverage is an upper bound (share
constraints are outside the flow).

## Consequences
- Position reports show coverage, shortfall and shortage groups rather than a net number.
- Dinic's algorithm handles the stated sizes in seconds; the benchmark test tracks it.
