# ADR 0021: Period end measured as balances, posted as differences; accounting policies as signed-off data

- Status: accepted (the policies need accounting sign-off before production use)
- Date: 2026-10-10

## Context
At month end the books need what the transaction postings cannot give:
- unrealised MtM of open derivatives;
- inventory at the lower of cost and net realisable value;
- the provision for emissions incurred but not yet surrendered;
- the release of free allocation booked at fair value;
- open foreign currency items at the closing rate.

How MtM is accounted for depends on why a book holds its contracts: trading versus own use versus hedging. That is a
judgement for accounting, not for the system. Accountants run the period end more than once while they close a
month, so a run must not double count.

## Decision
- `PeriodEndService.run(entity, period)` (ACCOUNTANT) measures each component as the balance it should have on the
  last day of the period and posts the difference to what is booked by then. A second run with unchanged inputs
  posts nothing; a run after a change posts only the change. Each run is recorded (`period_end_run`: what was
  required, booked and posted per component, the entries, and warnings).
- **Gate**: the period is not hard-closed, the next period is open, and there is an official valuation run of the
  period's last business day (weekdays; bank holidays are not modelled). Postings go through the adjustment path, so
  a soft-closed period accepts them; nothing is moved into the next period.
- **MtM** comes from the official valuation run, per book type. The `mtm_policy` (per entity, else default) decides:
  - `POST_PL`: the positive values go to derivative assets (1800) against unrealised gains (3500), and the negative
    values to derivative liabilities (2800) against unrealised losses (4500). Values are not netted.
  - `POST_OCI`: the same against the hedging reserve (2950).
  - `DISCLOSE_ONLY`: measured and recorded in the run, not posted.

  Seeded defaults: TRADING and HEDGING `POST_PL`, OWN_USE and COMPLIANCE `DISCLOSE_ONLY`. The MtM postings are
  reversed on the first day of the next period. Hedge accounting is not modelled; OCI is an option only.
- **Net realisable value** (`nrv_policy`: cost to sell, expiry window):
  - Certificates: each live lot with a cost basis is tested. Its net price is the contract price of the sale it is
    reserved to; else nil if it expires within the window; else the official vintage mark of its bucket. Cost to
    sell is deducted. The write-down is posted to 4310 against the allowance account 1490.
  - Allowances held as inventory: cost layers are tested against the official EUA price, posted to 1491. Allowances
    held as intangible assets are not tested here (IAS 36 impairment is outside this ADR).
  - A lot or product without a price or rate is reported in the run's warnings, not silently valued.
- **Emission provision** (IAS 37, net liability approach):
  - Emissions to date per installation, for the current year and the previous one:
    - verified emissions;
    - else monthly estimates of the months to date;
    - else the forecast pro rata.
  - The amount not yet surrendered is covered by allowances held, at the carrying amount the costing method would
    consume (FIFO or weighted average). Any shortfall is priced at the official EUA price.
  - The provision on 2300 is brought to that amount against emission costs (4200). Surrenders already debit 2300
    with the cost they consume, so a surrender between two runs leaves the provision unchanged.
- **Free allocation grant** (IAS 20, only when free allocation is booked at fair value): the year's deferred income on
  2400 is released to 4210 in proportion to emissions incurred to date over the year's emissions. When there are no
  emissions it is released evenly over the months.
- **FX revaluation**: open balances in foreign currency on invoiced receivables (1510) and payables (2210) are
  revalued at the period-end rate. The difference goes to unrealised FX gains (3910) or losses (4910) and is
  reversed on the first day of the next period.
- **Policies are data and need sign-off**:
  - Changes, including confirming a seeded default, go through `ACCOUNTING_POLICY_CHANGE` (four eyes,
    ACCOUNTANT). The approver is recorded as having signed off.
  - A run that uses a policy nobody has signed off says so in its warnings.
- **Commitment memos** for index-priced legs use an indicative price: the official curve price on the trade date plus
  the spread (formula legs and schedules without a curve price still get none).

## Consequences
- The month-end sequence is: sign off the valuation of the last business day, run the period end, review its
  components and warnings, soft-close, export, hard-close (M9 adds gates for this order).
- Inventory is measured as it is when the run happens, not as it was at the period end. Run the period end promptly
  after the month ends, or accept the timing difference.
- The provision and the grant release depend on emissions maintained in `etscompliance`; an entity without
  installations has no provision.

## Amendment (ADR 0045)
Cash flow hedge accounting is modelled: designated relationships under four eyes, effectiveness by the lower of on
every official valuation, and a period-end hedge component that puts the effective portion in the hedging reserve and
the rest in profit or loss. The designated proportion of a hedging trade leaves its book type's MtM. `POST_OCI` by book
type stays available but is no substitute for a designated hedge.
