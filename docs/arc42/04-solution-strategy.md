# 4. Solution strategy

| Problem | Strategy |
|---|---|
| GoO trades are agreed on criteria, not on specific certificates | A typed **criteria language** stored with the trade, bound per delivery date, compiled to SQL for search and evaluated in memory for allocation (ADR 0005) |
| Millions of certificates | **Lots as contiguous ranges** that split; the database enforces that live ranges never overlap (ADR 0006) |
| GoO net position is not a sum | **Coverage as max-flow** between lot classes and obligations; shortage groups explain shortfalls (ADR 0007) |
| Value in allocation | Scoring (opportunity cost, transfer fee, scarcity, expiry urgency) with a deterministic greedy solver; exact solver pluggable |
| Irreversible registry actions | Plan state machine: propose → four-eyes approve → hard reserve → instruct → confirm; instructions are idempotent and confirmed by the registry (ADR 0009) |
| Auditability | Append-only journals with hash chains (lots, ledger, audit); bitemporal versions for lots, trades and master data (ADR 0003) |
| Bookkeeping that accountants can change | **Posting rules as data** selected by event type and attributes; immutable journal with reversals (ADR 0008) |
| Keeping modules honest | Spring Modulith module metadata verified in the build; one schema per module; events through the transactional outbox (ADR 0001, 0004) |
