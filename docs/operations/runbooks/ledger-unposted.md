# Events not posted

**Signal.** `EtrmLedgerUnposted`: events the ledger could not post have waited for an hour. Each has a
`LEDGER_ATTENTION` task in the inbox for accountants.

**Confirm.** The task names the event, the rule and the reason: a missing FX rate, an index fixing not published
yet, too few cost layers, an account the rule needs. An hourly job retries what can be retried.

**Do.** Fix the cause (load the FX rate or fixing, correct the posting rule through a rule change), then **Retry**
in the task. When the event cannot be retried (its listener moved cost layers as well), book it by a manual journal
and **Dismiss** the task with the journal number as the reason.

**Verify.** The task closes; `etrm_ledger_unposted_open` returns to 0.
