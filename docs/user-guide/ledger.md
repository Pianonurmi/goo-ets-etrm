# Ledger and month end

Every business event posts to the general ledger through **posting rules**. The journal is append-only and
hash-chained: corrections are reversals, never edits.

## Ledger

The page opens with three cards: the period to close (with its status and blockers), posting rule changes waiting for
approval, and the active accounts. Click a card to go to month end, the rules or the accounts. The tabs are:

- **Entries** by period, with their lines; **Verify journal** checks the chain. Open an entry to **Reverse entry**
  (with a reason).
- **Trial balance** and **Accounts**.
- **Posting rules** map events to accounts. A change (**Propose a change**, or **New rule**) is approved by a
  second accountant in the inbox.
- **Manual entry** posts a manual journal (accountants).

## Month end

Choose the month with the period picker in the page header. Four cards show the period and what to do next, the
hard-close blockers, the latest period-end run and the latest GL export. The tabs are **Close**, **GL interface** and
**Policies**.

1. On **Close**, **Run period end** posts the revaluations and accruals the month needs (re-running posts only the
   difference).
2. **Soft close** stops ordinary postings; **Reopen** if something was missed.
3. On **GL interface**, **Export to GL** exports the month to the general ledger (**Export as CSV file** saves it;
   **CSV** downloads an earlier export). Mark it **Delivered**, then record the ERP's answer with **Acknowledge**
   (with its GL reference) or **Reject**. **Import GL balances** compares the ledger with the ERP.
4. **Hard close** is possible once the **Hard-close checklist** on the Close tab is clear (every export
   acknowledged, nothing pending).

On **Policies**, accounting policies (e.g. allowance costing) change through **Request policy change**, approved by
a second accountant.

> **Try it in the demo:** last month is soft closed and its export not yet acknowledged, which blocks the hard close.
