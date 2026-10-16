# ADR 0053: The coverage level and ratchets

- Status: accepted
- Date: 2026-10-16

## Context
The review (finding C9) found coverage rules that were either absent (web) or set below what the code already had,
so they could not catch a drop. A coverage figure is a floor against erosion, not a target: the tests that matter are
named in ADR 0049.

## Decision
- **Backend:** Kover over all modules, at least 90 % of lines and 65 % of branches (`etrm.root-conventions`), checked
  by `./gradlew build`. Generated jOOQ code, the application class and top-level function files are excluded. The
  bounds were set below what was measured (94 % and 69 %) and are a ratchet raised by hand: a pull request that
  lifts coverage past the next whole step raises them.
- **Web:** Vitest coverage over the `src` files the tests import (generated types and tests excluded), with floors for
  statements, branches, functions and lines in `vite.config.ts`. The floors are a **ratchet**: a local run raises
  each to the new whole percentage when coverage grows, and that change is committed with the work that earned it.
  CI never rewrites the floors, so a pull request that lowers coverage fails.
- A floor is lowered only in the pull request that explains why (code removed with its tests, a file moved out of
  scope, a wider measure), never to get a build through.
- **Increment 16 M5:** the page tests import whole pages, so 1,500 more lines came under measurement (400 more of
  them covered). The floors were reset once to that wider measure: 59 % statements, 57 % branches, 48 % functions,
  58 % lines. A file no test imports still counts for nothing, which the page tests narrow page by page.

## Consequences
- Coverage can only move up unless someone writes down why it went down.
- The backend ratchet is manual, unlike the web's: Kover cannot rewrite its own bounds, and raising them is a
  deliberate step in the pull request that earned it.
