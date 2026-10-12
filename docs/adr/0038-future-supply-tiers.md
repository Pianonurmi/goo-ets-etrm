# ADR 0038: Future supply as tiers, and plans that count on it

- Status: accepted (M5 of increment 8)
- Date: 2026-10-12
- Amends: ADR 0035 (value-based allocation), ADR 0036 (flow core and available to sell), ADR 0037 (portfolio view)

## Context
Only held certificates counted as supply. A desk that has bought next year's Nordic hydro forward, or that owns a
hydro plant, knows those certificates are coming. The system did not.

- Plans spent held lots on sales that a purchase would serve anyway, and left nothing for the sale only a held lot
  could serve.
- Available to sell and the portfolio view showed a book short that is covered by its own purchases.
- Coverage showed no way to tell "short" from "short until the purchase arrives".

## Decision
- **Three tiers of supply, counted cumulatively** (`allocation.api.SupplyTier`):
  - **HELD:** certificates held now (unchanged).
  - **CONTRACTED:** the open quantity of each GoO purchase schedule (BUY, firm or approved).
    - It is available from the schedule's delivery date.
    - It is assumed valid for twelve months from the start of its delivery window (`etrm.allocation.future.purchase-validity-months`).
    - What will arrive is only known by the purchase's criteria. A purchase therefore serves an obligation only when
      its criteria provably lie inside the obligation's (the implication of ADR 0016), and it belongs to a bucket on
      the same proof.
  - **EXPECTED:** the own devices of the entity, per production month from last month to twelve months ahead
    (`horizon-months`).
    - The quantity is the device's forecast for the month less what has already been issued for it. Issued means
      received root lots from issuance, for that device and production month.
    - It is available after the month's end plus the device's issuance lag (`issuance_lag_days`, default 30).
    - It is valid for twelve months after the production period.
    - The stand-in lot carries the device's attributes, so it is evaluated like a held lot.
- **Forecasts** (reference data, `ProductionForecastQuery`):
  - **Source:** a monthly figure per device, entered or loaded from CSV (`gsrn,month,expected_mwh,p90_mwh`), with
    every change kept in a history table. Where there is no forecast, capacity × the technology's load factor for the
    calendar month × the hours of the month.
  - **Load factors:** a seeded table with illustrative values. They are marked as such until replaced.
  - **Figures:** each has a central figure (P50) and a cautious one (P90). Its source (ENTERED, IMPORTED, LOAD_FACTOR)
    travels with it.
  - **Rights:** writes need MIDDLE_OFFICE.
  - **Event:** a change publishes `ProductionForecastChanged`.
- **In the problem:** future supply is extra lot classes whose lot is a stand-in (`FutureSupply`, `LotClass.future`).
  - **Eligibility:** a class is eligible for an obligation only if it is available by the obligation's delivery date.
  - **Registry:** a purchase without a delivery account has no known registry and is assumed to arrive where needed.
  - **Cost (ADR 0035):**
    - A purchase costs the mark of its bucket at the vintage it arrives in.
    - Expected issuance costs the best price it qualifies for, plus a reliability premium of
      value × (P50 − P90) / P50.
    - Both add a tie-break of 0.0001 EUR/MWh, so an equal held certificate is used first.
- **Plans count on future supply** (D6; `ProposeAllocation.futureSupply`, on by default):
  - **Quantities:** they take P90.
  - **Lines:** an assignment to held lots is a line as before, reserved and instructed. An assignment to future
    supply is a **virtual line** (`allocation_virtual_line`): the obligation, the source (`purchase:<schedule>` or
    `issuance:<gsrn>:<month>`), the quantity, when it is available and the score.
  - **Virtual lines are never reserved or instructed.** The obligation stays open for the part they cover, and a
    later plan serves it once the certificates are held.
  - **Value:** the plan's value includes them.
- **When future supply changes:**
  - **Triggers:** a purchase schedule is changed or closed, a forecast changes, or issuance is received.
  - **Plans affected:** the plans counting on that source are found by the virtual line's source reference.
  - **Proposed plans** go **STALE**: soft reservations released, `AllocationStale` published. The proposer
    re-plans; nothing re-reserves silently.
  - **Approved plans** keep their real lines. Their virtual lines are marked CHANGED, for the next plan to pick up.
  - **Safety:** this listener runs in a savepoint and never fails the transaction that published the event.
- **Views take P50 and choose a tier:**
  - **Available to sell and the portfolio view** take `tier` (HELD by default). The prepared flow of ADR 0036 is kept
    per entity and tier.
    - Beyond the held tier, its stamp includes a digest of the future supply itself. Purchases and forecasts need no
      stamp of their own, and a change through any pod is still seen on the next read.
    - Pre-trade checks stay on the held tier.
  - **The portfolio view** reports `futureMwh` per bucket. Future supply joins the netting, covered-within and free
    figures. Cheapest to deliver stays about held certificates.
  - **Coverage** per obligation adds covered-with-contracted and covered-with-expected (cumulative).
  - **`GET /api/v1/allocation/future-supply`** lists the future supply and coverage per delivery month and tier.

## Consequences
- **Held lots are kept for what only they can serve:**
  - A held lot is kept for the sale only it can serve when a purchase will serve the other.
  - A book whose purchases cover its sales no longer looks short.
- **Plans are explicit about what they count on:**
  - What a plan relies on that is not held yet is visible line by line, with the date it is needed by.
  - A change to that supply turns the plan stale instead of leaving it quietly wrong.
- **Expected issuance is an estimate.** Plans take the cautious P90, views the central P50. Neither is booked:
  valuation, the ledger and credit stay on held positions and trades.
- **Known limits:**
  - Load factors are illustrative, and a load-factor change publishes no event. Views see it through the stamp;
    proposed plans do not go stale on it.
  - Forecasts are per calendar month; a device commissioned mid-month counts the whole month.
  - A purchase received without a matching delivery record counts twice until it is matched: as a held lot and as
    the purchase's open quantity. Receipt matching (ADR 0015) closes the gap.
  - Side constraints of existing obligations are not in the flow, so available to sell stays an upper bound
    (ADR 0036).
