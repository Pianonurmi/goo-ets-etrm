# ADR 0045: Cash flow hedge accounting

- Status: accepted
- Date: 2026-10-13
- Amends: ADR 0021

## Context
- **A compliance buyer hedges forecast allowance purchases** with forwards bought for the purpose. Under IFRS 9 the
  forwards are derivatives at fair value; without hedge accounting their MtM swings profit or loss although the
  economic result is a fixed purchase price.
- **ADR 0021 offered `POST_OCI` per book type**, which moves a whole book's MtM to equity with no designation,
  documentation, effectiveness test or recycling. Accounts 2950 (hedging reserve) and the `MTM_OCI` rule existed; the
  rest did not.
- **Period end posts unrealised MtM and reverses it on the first day of the next period** (ADR 0021). Whatever hedge
  accounting does must fit that pattern, so re-runs stay idempotent and the journal append-only.

## Decision
### Relationships
- **One kind: cash flow hedges** of a highly probable forecast purchase (hedged by bought forwards) or sale (sold
  forwards) of allowances or certificates: product or bucket, quantity, expected date, currency.
- **Hedging instruments** are open, fixed-price trades of the entity, each wholly or for a proportion; a trade is
  designated at most in full across open relationships.
- **Prospective checks** compare critical terms (product, currency, delivery within 31 days of the expected date,
  ratio 80–125 %). They are shown, not enforced: IFRS 9 asks for an economic relationship, not a bright line.

### Four eyes
- **Designation**: an accountant proposes, a second accountant approves (`HEDGE_DESIGNATION`); the service and a
  database check refuse requester = approver. Approval locks the hedged item's official forward price on that day
  (the hypothetical derivative's price) and the instruments' MtM then, and issues the hedge documentation as a PDF
  through the document module (objective, item, instruments, ratio, checks, method).
- **Discontinuation**: proposed with a reason and whether the forecast is still expected, approved by a second
  accountant (`HEDGE_DISCONTINUATION`). Still expected: the reserve is frozen at its last measured amount. No longer
  expected: it is recycled, so everything goes to profit or loss.
- **No backdating**: a hedge applies from its designation date; earlier valuations are not measured.

### Effectiveness
- **On every official valuation run** of the entity (listener on `ValuationRunSignedOff`, in a savepoint so it never
  fails the sign-off), and again on demand at period end, each active hedge is measured once per run and kept
  immutable:
  - instruments' value M: the designated proportions of their official MtM (discounted, ADR 0044), base currency;
  - their change since designation ΔM = M − M₀;
  - hypothetical derivative H: a forward on the hedged item at the locked price, valued like a trade
    (`ValuationQuery.forwardValue`, same curves, rates and discounting);
  - **lower of**: the reserve takes ΔM when |ΔM| ≤ |H|, else H, when both move the same way, else nothing;
  - ineffectiveness = M − reserve (this includes M₀, the instruments' value before designation);
  - dollar offset ΔM / H in percent, for information.

### Period end
- The designated proportion of a hedging trade leaves its book type's MtM and is posted by its hedge, event
  `PeriodEndHedge`, dimension `hedge`, rule `HEDGE_PERIOD_END`: the instruments' value on 1800/2800, the reserve on 2950,
  ineffectiveness on 3500/4500. Like the books' MtM it is the difference from what is booked and is reversed on the
  first day of the next period. A discontinued hedge keeps its frozen reserve while its instruments are open.
- **Reclassification is by construction**: the instruments are physically settled forwards, so when they deliver the
  allowances are booked at the contract price, which is the hedged price. The reserve, reversed each month, leaves
  nothing behind to recycle. Cash-settled instruments are not designable for that reason.

## Consequences
- Fair value hedges, hedges of net positions and cost-of-hedging are not modelled.
- Reports: `HEDGE_EFFECTIVENESS` lists the measurements of a period.
- The demo designates one hedge with documentation and leaves a second in the inbox; measurements start with the next
  official valuation a visitor signs off.
- Accounting has to sign the method off like the other seeded policies; until then it is an implementation of the
  standard, not a policy.
