# ADR 0008: Posting rules as data

- Status: accepted
- Date: 2026-10-10

## Context
IFRS treatment of allowances (intangible versus inventory, free allocation at nominal versus fair value) and of GoO
(inventory versus own use) differs by entity and changes over time. Accountants must be able to adjust mappings without
a release.

## Decision
Business events are translated into `PostingFacts` (amounts, attributes, dimensions). Posting rules are rows selected
by event type and attribute conditions; entity-specific rules override defaults by rule code. A rule's lines name
accounts and the fact that supplies the amount. Entries are balanced in transaction currency, converted at the posting
date's FX rate, chained by hash and never edited.

## Consequences
- The default rule set ships as a resource and is seeded on start-up; changes are versioned rows.
- Facts are the contract between modules and accounting; new events only need a listener that produces facts.
