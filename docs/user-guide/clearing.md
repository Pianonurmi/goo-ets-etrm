# Clearing

EUA futures traded through a clearing house are paid every day: the clearing house moves the **variation margin**
through the clearing member's account each evening. The **Clearing** page computes what that margin should be,
compares it with the member's statement, and follows the cash held at the member as margin calls.

The page opens with four cards for the chosen clearing account: **Initial margin held**, **Cash at the member**,
**Open margin calls** and **Open breaks**. When the entity has more than one clearing account, choose one in the
page header. The tabs are **Accounts**, **Daily margin**, **Statements**, **Breaks** and **Margin calls**.

## Accounts

A **clearing account** is one legal entity at one clearing house, held through a clearing member, in one currency.
The **Accounts** tab lists them with the initial margin held, the cash at the member and the margin calls still to
pay. Choose a row to work on that account in the other tabs.

**New clearing account** (middle or back office) asks for a code, the clearing house (a counterparty of type
clearing house), the clearing member and the currency. The entity's allowance trades with a futures leg whose
clearing house is the account's are margined; a trade in another currency than the account's is refused, not
converted.

## Daily margin

The **Daily margin** tab lists the margin we computed for each business day in the period, newest first. Open a day
to see its lines: for each contract the position before, what was traded, the position after, the previous and the
current settlement price, and the variation margin. A gain is green and a loss red.

- A run never changes. If a trade is amended or a price corrected, the day is computed again and the new version
  replaces the old one, which stays on record; only the current version of each day is listed.
- Margin is computed every weekday evening. **Compute margin** (middle or back office) does it by hand for one
  business day, for example after the settlement price arrived late. A day without an official settlement price
  cannot be computed.
- The margin is booked in the ledger as a gain or loss on futures. Month end leaves margined futures out of the
  unrealised result, since it is already paid.

## Statements

The **Statements** tab holds the clearing member's statements, one per business day, with the variation margin,
initial margin, fees and cash balance the member reports. **Import statement** uploads a CSV file with one or more
days (or paste its content):

```
Date,Account,Record,Contract,Position,SettlementPrice,Amount,Currency
```

The records are `VM` (variation margin per contract), `IM` (initial margin), `FEE` and `CASH`. After the import the
page says how many new versions were stored, how many days were unchanged, and how many breaks are open. The same
figures again change nothing; other figures for a day replace its statement with a new version.

Open a statement to see its lines and the break it opened, if any. The initial margin of the latest day sets the
level held at the member; fees are charged to the books.

## Breaks

Each imported statement is compared with the margin we computed, contract by contract: the position after the day,
the variation margin and the settlement price. A difference is a **break**, one per account and day.

The **Breaks** tab shows open breaks first; the filter shows matched or accepted ones too. Open a break to see the
differences, **Ours** against **Theirs**:

| Difference | What it means |
| --- | --- |
| **No margin run** | The member sent a statement for a day we have no run for. Compute the margin. |
| **Position** | The position after the day differs. Check the trades. |
| **Variation margin** | The margin paid for a contract differs. |
| **Settlement price** | The member used another settlement price than the official one. |

A break closes in one of two ways:

1. **Matched:** a corrected trade, price or statement makes the two agree, and the task in the inbox closes by itself.
2. **Accepted:** a second person (not who imported the statement) accepts the member's figures with a reason. Use
   **Open the inbox task** on the break. Accepting posts nothing: the margin booked stays as computed, and a real
   difference is corrected at its source.

## Margin calls

A **margin call** opens when the cash at the member is below the initial margin required, after the latest
statement. Its amount is the shortfall. Only one call per account is open: the next statement cancels it and opens a
new one if the account is still short.

The **Margin calls** tab lists them (open ones by default); open one for its amount, who requested it and its
**Lifecycle**. A second person (back office or accountant, not who imported the statement) **pays** the call in the
inbox, which books the bank payment to the member, or **disputes** it with a reason.

Risk counts the initial margin and the cash at the clearing house as collateral posted, and the **margin liquidity**
limit caps the initial margin held plus the calls still open (see [Risk](risk.md)).

> **Try it in the demo:** the account `ECC-DEMO-FI` of `DEMO-FI` has about seven months of margin runs and fifteen
> statements. The **Breaks** tab has one open break with a **Variation margin** difference; open it and follow
> **Open the inbox task**. On **Margin calls** there is one paid call and one open one.
