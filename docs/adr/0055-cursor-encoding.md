# ADR 0055: Keyset cursors

- Status: accepted
- Date: 2026-10-16

## Context
Long lists (trades, registry instructions, the inbox, invoices, payments, valuation results) page with keyset
cursors since increment 14 (finding W6), each list with its own cursor type. The encoding was shared code
(`platform.paging.Cursor`) but not a recorded decision, so its limits were easy to miss.

## Decision
- **A cursor holds the sort key of the page's last row**, nothing else: no offset, no filter, no user. The next page is
  the rows after that key in the list's order, so rows added or removed meanwhile neither repeat nor skip a row.
- **Encoding:** the key's values as strings, joined by `|`, then URL-safe base64 without padding. Clients treat it as
  opaque and send it back as `cursor`; a cursor that does not decode, or whose values do not parse, is a 422
  (`Invalid cursor`), whichever list receives it.
- **Keys end with a unique column** (the id, a UUIDv7) after the sort columns, so the order is total. Only the last
  value may contain `|` (decoding splits into exactly as many parts as the key has); the others are ids, instants,
  dates and numbers, which never do.
- **Each list defines its own cursor** next to its query (`TradeCursor`, `InstructionCursor`, `InboxCursor`, ...),
  built with `Cursor.encode`/`decode` and `page(limit)`, which fetches `limit + 1` rows to know whether there is more.
- Cursors are not signed: they only say where to start, and every page is filtered by the caller's roles and entity
  scope again, so a forged cursor shows nothing the caller could not page to.

## Consequences
- Changing a list's sort order changes its cursor format; old cursors then fail with 422 and the client starts again.
  Cursors are not stored anywhere, so this costs at most a refresh.
