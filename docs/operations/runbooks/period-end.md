# Period end failed

**Signal.** A period end run reports an error, or the hard close is refused with its blockers.

**Confirm.** Month end screen: the run's warnings and components; the hard-close blockers name the failed gate
(entries not exported, export not acknowledged, period end not run or older than the last posting).

**Do.**
- Missing market data (curve snapshot, FX rate, valuation run not signed off): provide it, then run again; a run
  posts only the difference to what is booked, so running again is safe.
- Entries posted after the last run: run the period end again.
- Export not acknowledged: deliver and acknowledge the GL export from the GL interface.
- Events waiting in [events not posted](ledger-unposted.md) belong to the period: resolve them before the hard close.

**Verify.** The hard close succeeds; the period shows CLOSED.
