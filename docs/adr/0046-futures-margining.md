# ADR 0046: Futures margining

- Status: accepted
- Date: 2026-10-13
- Amends: ADR 0021, ADR 0022, ADR 0044

## Context
- **EUA futures are booked against the clearing house** (`ClearingStatus.CLEARED`, a CCP counterparty) and valued at
  the settlement price without discounting (ADR 0044). ADR 0022 names the margining of exchange trades as not
  modelled.
- **On the exchange the result of a future is paid every day.** The clearing house moves the variation margin through
  the clearing member's account each evening. A future has no unrealised MtM beyond one day, and month end treating it
  as an unrealised derivative (ADR 0021) books the same result twice once the margin is in the books.
- **The clearing member's statement is the authority** for what was paid. The system computes what the statement
  should show, so differences can be found and explained.

## Decision
### Clearing module
- **A new module `clearing`** (schema `etrm_clearing`). It depends on reference data, product, trade, market data and
  workflow APIs; ledger depends on its API, not the other way round.
- **Clearing account:** one per legal entity and clearing house (a counterparty of type `CLEARING_HOUSE`), with the
  clearing member it is held through and its currency. Created by back or middle office.
- **Which trades:** the entity's allowance trades with a futures leg, not draft, pending approval or cancelled, whose
  CCP (`regulatory.ccpCounterpartyId`, else the counterparty) is the account's clearing house. A trade in another
  currency than the account's is refused, not converted.

### Variation margin
- **Per contract and business day** `d` (weekdays), from the trade date to the contract's last trading day:
  - position before `d`: the signed quantity of the trades dated before `d`;
  - `VM = position before × (S_d − S_d−1) + Σ trades of d: signed quantity × (S_d − trade price)`;
  - `S_d` is the official `SETTLEMENT` quote of the contract's instrument dated `d` (none: refused, nothing guessed);
    `S_d−1` the latest settlement before `d`, needed only with a position;
  - each line is rounded half-even to cents; the run's total is the sum of its lines.
- **Runs are immutable and versioned.** A run is keyed by account, date and version, and carries a hash of its inputs
  (positions, trades of the day, prices). Computing a day again with the same inputs returns the current run; changed
  inputs (an amended or late trade, a corrected price) supersede it with the next version. Lines are append-only.
- **The evening job** (`MarginJob`, 19:30 Frankfurt on weekdays, ShedLock) computes the day for every active account;
  a missing settlement price is logged and the day can be computed later by hand.

### Clearing statements
- **The member's statement of a day** comes as CSV (`Date,Account,Record,Contract,Position,SettlementPrice,Amount,
  Currency`; records `VM` per contract, `IM`, `FEE`, `CASH`), one or more days per file, uploaded by back or middle
  office. Like runs, statements are immutable and versioned by a hash of their content: the same figures again change
  nothing, other figures supersede the day's statement.
- **Reconciliation** compares the current statement of a day with the current margin run, contract by contract:
  position after the day, variation margin, and the settlement price where the member gives one (a contract missing
  on one side is flat with no margin). A statement without a run is a break (`NO_RUN`). It runs when a statement
  is imported and again when a new run is computed.
- **One break per account and day while open**, following the current statement and run. It closes as `MATCHED`
  when a corrected trade, price or statement makes the two agree, or as `ACCEPTED` when a second person (not who
  imported the statement; the service and a database check refuse it) accepts the member's figures with a reason,
  through the `CLEARING_BREAK` inbox task. Acceptance posts nothing: the margin booked stays as computed, and a real
  difference is corrected at its source.
- **Initial margin** is a level: the statement of the account's latest day sets it (a day without an `IM` record
  requires none), and an earlier day re-sent does not move it. Its change moves cash at the member (1960) into
  initial margin posted (1970) and back. **Fees** are charged to 4610 against 1960; a new version of a day posts only
  the change. Event `ClearingStatement`, rule `CLEARING_STATEMENT`. The cash balance is stored for margin calls
  (M3).

### Margin calls
- **A call** opens when the account's latest statement (once per imported file, its last day) shows the cash at the
  member below the initial margin required; the amount is the shortfall. At most one call per account is open: the
  next statement cancels it and opens a new one if still short, since its shortfall already includes the unpaid
  amount.
- **Paid or disputed under four eyes** through the `MARGIN_CALL` inbox task (back office or accountant, not who
  imported the statement; a database check too). Paying posts `MARGIN_CALL_PAYMENT` (event `MarginCallPaid`): bank
  1900 to cash at the member 1960, dated the decision. A dispute needs a reason. Returning excess cash is not
  modelled.

### Risk
- **Credit exposure to the clearing house**: margined futures are left out of MtM and PFE. The initial margin and
  the (positive) cash the house holds count as collateral posted, so they add to the exposure.
- **`MARGIN_LIQUIDITY` limit**: initial margin held plus open margin calls over the entity's clearing accounts, in
  the limit's currency; four eyes like every limit.

### Delivery
- **A margined future delivers at the final settlement price**: the contract's settlement price on its last trading
  day (`ClearingQuery.finalSettlement`). The variation margin has already paid the difference between the trade price
  and that price, so trade price × quantity is paid exactly once over the life of the future.
- **Settlement items** of a margined future take that price (a manual price fixing still wins); until it is stored
  the item is parked `NEEDS_ATTENTION` with the reason, and a re-evaluation prices it. **Allowance sale
  instructions** carry it as their unit price, so the registry movement and the cost of sale use it too. A
  future that is not margined (no clearing account) still delivers at its trade price.
- Allowances received on a bought future are costed by their registry movement like any receipt; back office
  enters the final settlement price there.

### Accounting
- **`VariationMarginComputed`** carries the run and the total of the version it supersedes. Ledger posts the
  difference (`FUTURES_VARIATION_MARGIN`, event `VariationMargin`, dimension `clearingAccount`): a gain debits 1960 cash
  at the clearing member and credits 3600 realised result on futures, a loss the reverse. A re-run with the same total
  posts nothing; a correction posts only the change. Postings dated in a closed period move to the next open one like
  any automatic posting.
- **Period end leaves margined futures out of the MtM:** trades counted by an active clearing account's runs are
  excluded from the book types' MtM (`ClearingQuery.marginedTradeIds`). Their valuation still shows on the
  valuation screens and in risk.
- **Futures are not hedging instruments** in cash flow hedges (ADR 0045): their result is realised daily, so there is
  no unrealised value to put in the reserve.
- New accounts: 1960 cash at clearing member (variation margin), 1970 initial margin posted, 3600 realised result on
  futures, 4610 clearing and exchange fees. Existing entities get them on their next posting.

## Consequences
- The ledger's futures result is realised P&L day by day; the realised total over a future's life equals the
  difference between the trade price and the final settlement price.
- Statement layouts of real clearing members differ; the CSV above is this system's own and is mapped to by hand.
- Margin depends on official settlement prices of every business day; a gap stops that day's margin until a price is
  stored. Exchange holidays are treated as business days (no calendar yet); a run on a holiday is refused for want of
  a price.
