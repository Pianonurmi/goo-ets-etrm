# Screens

Each screen lists the operations it calls (operation ids from `apps/api/openapi/etrm-api.json`) and who can act
there. Read-only parts are open to every signed-in user unless noted. The API enforces roles, four eyes and
legal-entity scope; the UI only mirrors them.

| Screen | Route | Operations | Acting roles |
|---|---|---|---|
| Home | `/` | `ui_me`, `referenceData_legalEntities`, `workflow_inbox` (actionable, first five), `workflow_counts` | everyone |
| Approval inbox | `/inbox` | `workflow_inbox` (cursor paging, filters in the URL), `workflow_taskTypes`, `workflow_counts`, `workflow_bulk` | candidates of each task type |
| Trade blotter | `/trades` | `trade_search` (one query per page, keyset cursor, filters and `q` in the URL), `referenceData_counterparties`, `referenceData_books`; tab Open deliveries (`?view=deliveries`): `trade_obligations` | everyone in scope |
| Trade capture and amendment | `/trades/new`, `/trades/:tradeId/amend` | `trade_capture`, `trade_amend`, criteria builder, `referenceData_registryAccounts`, `product_allowances`, `product_futuresContracts` (futures), `marketData_indices` (index pricing) | TRADER, MIDDLE_OFFICE |
| Trade detail | `/trades/:tradeId` | `trade_trade`, `trade_versions` (diff per version), `confirmation_forTrade`, `confirmation_issue`, `confirmation_receive` (their terms entered by hand), `valuation_tradeMtm`, `trade_confirm`, `trade_cancel`, link to the approval task, `risk_preTradeCheck` (Check limits), per schedule `trade_recordDelivery`, `delivery_deliverAllowances`, `delivery_matchReceipt` with `trade_obligation`, `trade_settle`, documents tab (`document_forOwner`, `document_document`, `document_verify`) | confirm, record delivery, settle: MIDDLE_OFFICE, BACK_OFFICE; allowance transfer also REGISTRY_OPERATOR; check limits: RISK, MIDDLE_OFFICE, TRADER, AUDITOR; amend/cancel: TRADER, MIDDLE_OFFICE |
| Confirmations | `/confirmations` (`view` and `counterparty` in the URL) | `confirmation_queue` (the selected entity), `confirmation_receiveCsv` | upload: MIDDLE_OFFICE, BACK_OFFICE; read in scope |
| Confirmation detail | `/confirmations/:confirmationId` | `confirmation_confirmation`, `confirmation_forTrade`, `document_content` (our PDF), link to the `CONFIRMATION_BREAK` task | override or dispute (inbox): MIDDLE_OFFICE, BACK_OFFICE, never the person who received it |
| Allocation workbench | `/allocation` | `allocation_obligations`, `allocation_coverage`, `allocation_plans`, `allocation_whatIf` (stores nothing), `allocation_propose` | propose and what-if: TRADER, MIDDLE_OFFICE, BACK_OFFICE |
| Allocation plan | `/allocation/plans/:planId` | `allocation_plan`, link to the `ALLOCATION_APPROVAL` task, `allocation_cancel` | approve (inbox): MIDDLE_OFFICE, BACK_OFFICE, never the proposer |
| Positions | `/positions` | `position_goo` (buckets, coverage, expiry ladder chart), `position_ets` (compliance ladder by year) | everyone in scope |
| Market data | `/market-data` (tabs: quotes, suspect, FX, instruments, imports) | `marketData_quotes`, `marketData_recordQuote`, `marketData_suspect`, `marketData_acknowledge`, `marketData_fxRates`, `marketData_recordFxRate`, `marketData_pullEcb`, `marketData_instruments`, `marketData_importFormats`, `marketData_importFile` (multipart) | writes: MIDDLE_OFFICE, RISK |
| Curves | `/market-data/curves`, `/market-data/snapshots/:snapshotId` | `curve_curves`, `curve_snapshots`, `curve_build`, `curve_snapshot`, `curve_override`, `curve_submit`, link to the `CURVE_SIGN_OFF` task | build/submit: MIDDLE_OFFICE, RISK; sign-off (inbox) by another person |
| Valuation | `/valuation`, `/valuation/runs/:runId` | `valuation_runs`, `valuation_run`, `valuation_runDetail`, `valuation_results`, `valuation_submit`, `valuation_explain` (defaults to the two latest official runs) | run/submit: MIDDLE_OFFICE, RISK; read: RISK, MIDDLE_OFFICE, ACCOUNTANT, AUDITOR, TRADER |
| Risk: limits | `/risk` | `risk_limits` (active, or all), `risk_utilisation` per limit, `risk_measure`, `risk_requestLimit` | request: RISK (approval by another RISK user in the inbox); read: RISK, MIDDLE_OFFICE, TRADER, AUDITOR |
| Risk: exposures, breaches, credit support, PFE | `/risk?tab=exposures`, `breaches`, `credit-support`, `pfe` | `risk_exposures` (bar chart and grid), `risk_breaches` joined with `risk_limits`, `risk_creditSupport`, `risk_requestCreditSupport`, `risk_pfeAddOns` | credit support: RISK, BACK_OFFICE (approval in the inbox) |
| Limit detail | `/risk/limits/:limitId` | `risk_limit`, `risk_utilisation`, `risk_breaches`, link to the `LIMIT_CHANGE` task, `risk_requestLimit` with `replaces` | change: RISK |
| EU ETS compliance | `/ets` | `etsCompliance_compliance` (year in the URL), `etsCompliance_deadlines` (calendar), `etsCompliance_register` | register: MIDDLE_OFFICE, BACK_OFFICE |
| Installation | `/ets/installations/:installationId` | `etsCompliance_installation`, `etsCompliance_installationCompliance`, `etsCompliance_emissions`, `etsCompliance_recordEmissions`, `etsCompliance_recordVerifiedEmissions`, `etsCompliance_freeAllocation`, `etsCompliance_setFreeAllocation`, `etsCompliance_surrenders`, `etsCompliance_requestSurrender` | estimates and verified emissions: MIDDLE_OFFICE, BACK_OFFICE, RISK (verified ones approved in the inbox); free allocation: MIDDLE_OFFICE, BACK_OFFICE; surrender: BACK_OFFICE, REGISTRY_OPERATOR |
| Ledger: entries | `/ledger` (period, account and source in the URL) | `ledger_entries` (`accountCode`, keyset paging with `afterEntryNumber`), `ledger_entry`, `ledger_reverse`, `ledger_postManual`, `ledger_accounts`, `ledger_verify` | reverse and manual entries: ACCOUNTANT, BACK_OFFICE; read: ACCOUNTANT, AUDITOR, BACK_OFFICE in scope |
| Ledger: trial balance, accounts, posting rules | `/ledger?tab=trial-balance`, `accounts`, `rules` | `ledger_trialBalance`, `ledger_accounts`, `ledger_rules`, `ledger_ruleHistory`, `ledger_ruleChanges` | read only |
| Month end: close | `/month-end` (period in the URL, default the previous month) | `ledger_periods`, `ledger_closePeriod` (soft or hard), `ledger_reopenPeriod`, `ledger_hardCloseBlockers` (checklist), `periodEnd_runs`, `periodEnd_run` | ACCOUNTANT |
| Month end: GL interface | `/month-end?tab=gl` | `ledger_exportFormats`, `ledger_exports`, `ledger_export`, `ledger_delivered`, `ledger_acknowledge`, `ledger_reject`, `ledger_reconciliation`, `ledger_importBalances` (pasted lines) | writes: ACCOUNTANT; read: ACCOUNTANT, AUDITOR |
| Month end: policies | `/month-end?tab=policies` | `periodEnd_mtmPolicies`, `periodEnd_nrvPolicy`, `periodEnd_policyChanges`, `periodEnd_requestPolicyChange` | request: ACCOUNTANT (approval by another in the inbox) |
| Reports | `/reports` (`report` in the URL) | `reporting_catalogue` (filtered by the user's roles), `reporting_report` (JSON into a grid, or CSV download; run number and hash from the response headers), `reporting_exports`, `reporting_emirEvents`, `reporting_emirCompleteness`, `reporting_emirFieldRules` | the roles each report names |
| Reference data | `/admin/reference` (tabs: entities, counterparties, registries, devices) | `referenceData_legalEntities`, `referenceData_createLegalEntity`, `referenceData_books`, `referenceData_createBook`, `referenceData_registryAccounts`, `referenceData_createRegistryAccount`, `referenceData_counterparties`, `referenceData_createCounterparty`, `changeRequest_requestCounterpartyAccountChange`, `referenceData_registries`, `changeRequest_requestRegistryChange`, `referenceData_productionDevices`, `referenceData_history`, `partyDetails_*` | create: MIDDLE_OFFICE (accounts and devices also BACK_OFFICE, REGISTRY_OPERATOR); addresses and VAT: MIDDLE_OFFICE, BACK_OFFICE, ACCOUNTANT; change requests: MIDDLE_OFFICE, BACK_OFFICE, REGISTRY_OPERATOR, ACCOUNTANT (approved by another person in the inbox) |
| Taxonomy | `/admin/taxonomy` (`scheme` in the URL) | `taxonomy_list` (`all=true`), `taxonomy_create`, `taxonomy_update`, `taxonomy_regionCountries`, `taxonomy_setRegionCountries` | MIDDLE_OFFICE |
| Products | `/admin/products` (tabs: buckets, allowances, futures) | `product_buckets`, `product_createBucket` (criteria builder), `product_updateBucket`, `product_allowances`, `product_futuresContracts`, `product_createFuturesContract`, `product_deactivateFuturesContract` | MIDDLE_OFFICE |
| Change requests | `/admin/change-requests` (`status` in the URL) | `changeRequest_changeRequests`, `changeRequest_cancel`, link to the `REFERENCE_DATA_CHANGE` task | cancel: the requester or ADMIN |
| Audit trail | `/audit` (filters in the URL) | `audit_events` (paged by `afterSeq`), payload of one event | AUDITOR, ACCOUNTANT, MIDDLE_OFFICE, BACK_OFFICE, RISK |
| Inventory: certificates | `/inventory` | `inventory_lots` (selected entity; status and expiry filters in the URL) | everyone in scope |
| Inventory: criteria search | `/inventory?tab=search` | `product_criteriaCatalogue`, `product_validate` (as you type), `taxonomy_list`, `referenceData_registries`, `referenceData_productionDevices`, `inventory_search` | everyone in scope |
| Inventory: allowances | `/inventory?tab=allowances` | `inventory_holdings`, `inventory_movements` | everyone in scope |
| Lot detail | `/inventory/lots/:lotId` | `inventory_lot`, `inventory_history`, `inventory_asOf`, `inventory_split`, `inventory_cancel`, `inventory_withdraw`, `registry_transfer` (new transfer with the route shown) | operations: BACK_OFFICE, REGISTRY_OPERATOR |
| Registry instructions | `/registry` (`view`, `registry` and `asset` in the URL) | `registry_search` (keyset cursor, the selected entity), `referenceData_registries`, `registry_transfer` | read: REGISTRY_OPERATOR, BACK_OFFICE, MIDDLE_OFFICE, ACCOUNTANT, RISK, AUDITOR in scope; transfer: REGISTRY_OPERATOR, BACK_OFFICE, MIDDLE_OFFICE (approval by another person in the inbox) |
| Instruction detail | `/registry/instructions/:instructionId` | `registry_instruction`, `registry_confirm`, `registry_fail`, `registry_cancel`, `registry_retry`, links to `INSTRUCTION_APPROVAL` and `INSTRUCTION_FOLLOW_UP` tasks | REGISTRY_OPERATOR, BACK_OFFICE on the holder |
| Transfer routes, statement formats | `/registry?tab=routes` (`from` in the URL), `/registry?tab=formats` | `registry_routes`, `registry_upsertRoute`, `registry_formats` | routes: MIDDLE_OFFICE, BACK_OFFICE |
| Reconciliation: breaks | `/reconciliation` (`closed` and `type` in the URL) | `inventory_breaks` (limited to the selected entity's accounts), `inventory_ageing`, `referenceData_registryAccounts` | read: REGISTRY_OPERATOR, BACK_OFFICE, MIDDLE_OFFICE, ACCOUNTANT, RISK, AUDITOR in scope |
| Reconciliation: statements | `/reconciliation?tab=statements` (`account` in the URL) | `inventory_statements`, `registry_importHoldings` (CSV as text, reconciled at once), `registry_importTransactions` (registry-wide) | REGISTRY_OPERATOR, BACK_OFFICE on the account's holder; transactions need every entity |
| Break detail | `/reconciliation/breaks/:breakId` | `inventory_reconciliationBreak`, `inventory_lot`, `break_assign`, `break_confirmInstruction`, `break_withdrawLot`, `break_recordReceipt`, `break_replaceLot`, `break_correctLot`, `break_correctHolding`, `break_accept`, links to `RECONCILIATION_BREAK` and `BREAK_ACCEPTANCE` tasks | REGISTRY_OPERATOR, BACK_OFFICE; acceptance approved by another BACK_OFFICE or MIDDLE_OFFICE user in the inbox |
| Clearing: accounts | `/clearing` (`account` in the URL) | `clearing_accounts`, `clearing_createAccount`, `clearing_marginPosted`, `clearing_breaks`, `clearing_marginCalls` (KPIs) | create: BACK_OFFICE, MIDDLE_OFFICE; read: BACK_OFFICE, MIDDLE_OFFICE, ACCOUNTANT, RISK, AUDITOR in scope |
| Clearing: daily margin | `/clearing?tab=margin` (`from`, `to`, `run` in the URL) | `clearing_runs`, `clearing_run`, `clearing_computeMargin` | compute: BACK_OFFICE, MIDDLE_OFFICE |
| Clearing: statements | `/clearing?tab=statements` (`statement` in the URL) | `clearing_statements`, `clearing_statement`, `clearing_importStatements` (CSV as text) | import: BACK_OFFICE, MIDDLE_OFFICE |
| Clearing: breaks and margin calls | `/clearing?tab=breaks`, `/clearing?tab=margin-calls` (`call` in the URL) | `clearing_breaks`, `clearing_marginCalls`, `clearing_marginCall`, links to `MARGIN_CALL` tasks | calls paid or disputed in the inbox by BACK_OFFICE or ACCOUNTANT, not the importer |
| Clearing break detail | `/clearing/breaks/:breakId` | `clearing_clearingBreak`, link to the `CLEARING_BREAK` task | accepted in the inbox by BACK_OFFICE or MIDDLE_OFFICE, not the importer |
| Settlement items | `/settlement` (`view` in the URL: needs attention, open, invoiced, all) | `settlement_items`, `settlement_fixPrice`, `settlement_reevaluate`, `settlement_run` (invoice run), `settlement_registerPurchaseInvoice` | fix price: MIDDLE_OFFICE, BACK_OFFICE; evaluate again: ACCOUNTANT, BACK_OFFICE, MIDDLE_OFFICE; runs and purchase invoices: BACK_OFFICE, ACCOUNTANT; read: BACK_OFFICE, ACCOUNTANT, MIDDLE_OFFICE, RISK, AUDITOR in scope |
| Invoices | `/settlement?tab=invoices` (status, kind and counterparty in the URL) | `settlement_invoices`, `settlement_export` (issued, CSV) | read as settlement items |
| Invoice detail | `/settlement/invoices/:invoiceId` | `settlement_invoice`, `settlement_submit`, `settlement_discard`, `payment_creditNote`, `payment_allocations`, `document_content` (PDF), link to the `INVOICE_APPROVAL` task | BACK_OFFICE, ACCOUNTANT; approval (inbox) by another person |
| Payments | `/settlement?tab=payments` (`unapplied` in the URL) | `payment_list`, `payment_record` (applied to chosen invoices or to numbers in the remittance), `payment_importCsv` (bank statement lines as `text/csv`), `payment_allocate` | BACK_OFFICE, ACCOUNTANT |
| Netting | `/settlement?tab=netting`, `/settlement/netting/:nettingId` | `payment_nettingStatements`, `payment_prepareNetting` (counterparties with a netting agreement), `payment_nettingStatement`, `payment_agree` (never the preparer), `payment_settle` (with the net payment), `payment_cancelNetting`, `document_content` (PDF) | BACK_OFFICE, ACCOUNTANT |
| VAT rules | `/settlement?tab=vat-rules` | `settlement_vatRules`, `settlement_proposeVatRule` (`VAT_RULE_APPROVAL` in the inbox), `settlement_determine` | propose: ACCOUNTANT, BACK_OFFICE; approve: another ACCOUNTANT |
| Settlement reports | `/settlement?tab=reports` (`report` and its parameters in the URL) | `payment_ageing`, `payment_cashForecast`, `payment_vat`, `payment_ecSalesList` | ACCOUNTANT, BACK_OFFICE, MIDDLE_OFFICE, RISK, AUDITOR in scope |
| Task detail | `/inbox/:taskId` | `workflow_task`, `workflow_approve`, `workflow_reject`, `workflow_acknowledge`, `workflow_claim`, `workflow_release`, `workflow_assign`, `workflow_comment` | candidates; never the requester when the type is four-eyes |

## Inbox rules mirrored in the UI (`features/inbox/model/decisions.ts`)

- A task can be decided while it is open, by a user holding one of its candidate roles (or ADMIN). With four eyes
  (the default), the requester may not decide.
- An assignee only marks who is working on a task. Any candidate may still decide it, as in `WorkflowService`.
- The task type's `reasonRequiredFor` decides when a reason is mandatory.
- Bulk decisions apply to the selected tasks the user may decide. The server reports each refusal.
- Subjects link to their screens once those exist (`model/subjects.ts`).

## Criteria builder (`features/criteria`)

- Built from `GET /api/v1/products/criteria/catalogue`: one entry per lot attribute with its operators and value
  source (taxonomy scheme, country, registry, device, enum, boolean, number, date, text).
- The tree is edited through the pure functions in `model/tree.ts` (add, replace, remove, wrap, negate, toggle).
- The server describes the criterion and scores its specificity as you type, once every condition has a value.
- The JSON tab edits the same value. The criterion travels exactly as the API defines it, a discriminated union on
  `type`.
- Totals on the inventory screens add whole certificate counts (MWh). No money is summed in the browser.

## Trades (`features/trades`)

- The capture form keeps decimals as strings (`model/tradeForm.ts`), checks what the browser can, and maps the
  server's 422 violations onto the fields. Anything it cannot place is listed at the top of the form.
- Approval runs through the inbox (four eyes): the detail page links to the open `TRADE_APPROVAL` task.
- Pre-trade findings (credit, limits) are shown as the trade carries them. A finding that needs risk approval is red.
  Check limits runs them again against today's limits (nothing is stored).
- The form covers every instrument: a future picks its contract (`withFuture` sets product, currency, venue and the
  single delivery; only exchanges and clearing houses are offered), a leg priced by index names the index and the
  price is the spread, a broker and master agreement are optional, and an intercompany counterparty needs the mirror
  book of the other own entity. The rules are pure functions in `model/tradeForm.ts` with Vitest.
- Delivery actions per schedule (`model/operations.ts`): record a delivery by hand (with an idempotency key from
  `shared/ids.ts`, which also works on plain HTTP), transfer allowances through the Union Registry for a fixed-price
  sale, match a received lot or allowance movement to a purchase; a delivered trade can be marked settled.
- A GoO sale needs the counterparty's registry account before it can be confirmed. The form says so.
- The REST guards for recording deliveries, settling and issuing our confirmation require middle or back office.
  Module flows (allocation, registry) are unaffected.

## Risk and EU ETS compliance (`features/risk`, `features/ets`)

- Limit changes and credit support apply once a second risk analyst approves them in the inbox. The limit detail page
  links to the open `LIMIT_CHANGE` task; a change of an active limit is a new request that `replaces` it.
- The form shows the scope fields each limit type uses (`model/limits.ts`, mirroring the service's validation).
- Utilisation is the latest measurement. A limit that was never measured shows "Not measured". "Measure now" runs
  the same measurement as the scheduled job.
- Breach tasks (`LIMIT_BREACH`) and credit support tasks link to the risk tabs. Verified emissions and deadline
  alerts link to the compliance page.
- Deadlines are grouped by month and coloured met, overdue, due within 30 days (the first reminder stage) or
  upcoming (`model/deadlines.ts`).
- Quantities of allowances are whole tonnes. Net and outstanding figures come from the server.

## Ledger, month end and reports (`features/ledger`, `features/monthend`, `features/reports`)

- The journal pages forward by entry number (`afterEntryNumber`), 200 entries a page. The account filter keeps
  entries with a line on that account. An entry opens in a drawer with its lines, dimensions, source and hash, and
  can be reversed with a reason. Entries are never edited.
- Manual entries need two complete lines, each with an account and exactly one amount (`model/manual.ts`). The
  server checks the balance and the period.
- The hard-close checklist is the server's list of blockers. Hard close stays disabled while there are any.
- GL balances are pasted as `account;balance;currency` lines (`model/glBalances.ts`) and reconciled by the server.
- A report's parameters are generated from its definition. The legal entity comes from the header selector. Every
  run, on screen or as CSV, is logged with its number and content hash.

## Administration (`features/admin`, `features/audit`)

- Reference data is versioned by the database. History drawers show each version with the fields it changed
  (`model/history.ts`).
- Registry integration modes, counterparty registry accounts and bank accounts change only through change requests
  approved by a second person in the inbox. Our own registry accounts, books, addresses and VAT numbers change
  directly, by the roles above.
- A bucket's criterion is fixed once created. To change it, retire the bucket and create a new one, so positions
  stay explainable.
- Taxonomy nodes are retired by an end date, never deleted.

## Settlement (`features/settlement`)

- Settlement items come from recorded deliveries. The items tab opens on those that need attention: an item waits
  for a price (index or formula trades, fixed by hand with `settlement_fixPrice`) or for a VAT rule or VAT number
  (evaluated again once the reference data is fixed). Actions are pinned to the right of the grid.
- An invoice run drafts one sales invoice per counterparty and currency; the drafts are listed with links. A draft
  is submitted for approval (`INVOICE_APPROVAL`) and issued by a second person in the inbox, which numbers it,
  freezes the party details and renders the PDF. Discarding a draft reopens its items.
- A purchase invoice is matched to the supplier's open purchases (all by default, or the ones ticked); the server
  checks net and VAT within its tolerance. No amounts are added up in the browser.
- A credit note reverses an issued invoice in full and goes through the same approval.
- Payments: a recorded payment is applied to the invoices chosen, else to invoice numbers in the remittance text;
  what is left stays unapplied and can be allocated later. Bank statement CSV goes up as text (`shared/api/upload.ts`).
- Netting: one person prepares a statement, another records the counterparty's agreement; settling needs an
  unapplied payment of the net amount in the right direction (none when the invoices offset exactly).
- Rules mirrored in the UI: `model/settlement.ts` (actions per invoice state, item views, purchase candidates) and
  `model/payments.ts` (netting actions and four eyes, payments that fit a statement, invoices a payment can go to,
  report defaults).

## Registry (`features/registry`)

- The instruction queue opens on open work (waiting for approval, pending, with the registry, unknown); "needs
  attention" shows unknown and failed ones. Paging uses the search endpoint's cursor.
- Actions follow the instruction's state (`model/instructions.ts`, mirroring `RegistryInstruction`): confirm and
  fail while the registry has it (or a pending manual-channel instruction), cancel before it is sent or after it
  failed, retry after a failure or an unknown outcome (back through approval). An instruction being dispatched is
  left to the dispatcher.
- A manual transfer, from the registry screen or a lot, shows the route between the registries (fee, days, or that
  it cannot be done) before it is submitted, and waits for a second person in the inbox.
- Statement formats are shown read-only; they change through the API (`registry_upsertFormat`, middle or back
  office).

## Reconciliation (`features/reconciliation`)

- A holdings upload is read with the registry's statement format and reconciled at once. New differences become
  breaks; breaks the registry no longer shows are cleared automatically. A lot in transfer that the statement no
  longer shows counts as executed by the registry.
- The break detail offers only the resolutions the server allows for the break's type and its lot's status
  (`model/breaks.ts`, mirroring `BreakResolutionService`): confirm the transfer or withdraw our lot when the
  registry lacks certificates, record a receipt when it has more, replace or correct our lot when attributes differ,
  book the difference for allowance balances. Any open break can be accepted as it is; a second person approves the
  acceptance in the inbox.
- The ageing figures and the queue show the selected entity's accounts.

## Confirmations (`features/confirmations`)

- The queue shows counterparty confirmations to work on (received, mismatched, disputed); the counterparty comes
  from the trade (`ConfirmationView.counterpartyId`).
- The detail lines up their terms against the confirmation of ours they were matched with, field by field, and
  marks what the matcher recorded (MISMATCH or NOTE). Fields are named as the matcher names them (`legs[1].price`).
- Their confirmation can be entered by hand from the trade's confirmations tab: it starts from our terms and takes
  the counterparty's quantity, price, amount and first delivery date (`model/confirmations.ts`, `enteredTerms`).
- A mismatch opens `CONFIRMATION_BREAK`: approving overrides it (the trade is confirmed with method OVERRIDE),
  rejecting disputes it.

## Increment 7 M8: the remaining operations

Every API operation now has a screen, except the ones below (`apps/web/api-only.json`, checked by
`npm run coverage:api`).

- **Inventory:** receive certificates (header), reserve, release and correct on the lot detail (`LotOperations.tsx`),
  an expiry ladder chart, the lot journal check, allowance movements and transfers on the Allowances tab.
- **Registry and reconciliation:** Union Registry allowance transfer, GoO cancellation for a beneficiary from the lot,
  the statement format editor (`JsonEditorModal`), statement detail with reconcile again.
- **Allocation:** open a manual obligation, the obligation drawer (`?obligation=<id>`) with share constraints (only
  while OPEN) and close with a reason.
- **Market data and curves:** new instrument, aliases, an Indices tab with new index and fixing lookup, import
  format editor; new curve, official curve as of a date, forward and vintage price lookup.
- **Valuation and positions:** the official run as of a date; a Snapshots tab with take snapshots.
- **Reference data:** counterparty, legal entity and device detail and edit (changed fields with the expected
  version), new device, default book per own registry account, history as of a date. The accounting policy is not
  edited here: it changes through month end with a second accountant.
- **Ledger and month end:** propose a posting rule change (approved in the inbox), download a GL export as CSV.
- **ETS, reports, settlement:** installation register; download a logged export again, record an EMIR rejection;
  settlement item drawer (`?item=<id>`).
- **Inbox:** the requester cancels (withdraws) a trade approval, which cancels the trade
  (`TaskHandler.onWithdraw`). Other approvals are decided, never withdrawn, so their subject never waits.

API-only operations and why:

| Operation | Reason |
|---|---|
| `trade_approve` | Approval goes through the TRADE_APPROVAL task in the inbox (workflow_approve), which records the decision; kept for API clients |
| `trade_reject` | Rejection goes through the TRADE_APPROVAL task in the inbox (workflow_reject); kept for API clients |
| `allocation_approve` | Plan approval goes through the ALLOCATION_APPROVAL task in the inbox (workflow_approve); kept for API clients |
| `allocation_reject` | Plan rejection goes through the ALLOCATION_APPROVAL task in the inbox (workflow_reject); kept for API clients |
| `trade_trades` | Unpaged list for integrations; the blotter uses the keyset-paged trade_search |
| `registry_open` | Open-instruction list for integrations; the registry screen uses the paged instruction search |
| `registry_surrender` | EU ETS surrenders are requested from the installation (etsCompliance_requestSurrender), which attributes them; the raw instruction would skip attribution |
| `inventory_importStatement` | Structured holdings import for registry API adapters; people upload statement files through the registry format profiles (registry statements CSV) |
| `inventory_expire` | Trigger for the nightly lot expiry job, for operations scripts |
| `marketData_rate` | FX conversion service for other systems; the market data screen shows the stored rates |
| `audit_event` | Single audit event by id for integrations; the audit trail shows events in full in its list |
| `changeRequest_changeRequest` | Single change request by id; the change request list and the inbox task show it in full |
| `referenceData_book` | Single book by id; books are shown from the legal entity's book list |
| `product_bucket` | Single product bucket by code; the products screen shows buckets from its list |
| `taxonomy_get` | Single taxonomy node; the taxonomy editor loads the whole tree |
| `taxonomy_subtree` | Taxonomy subtree for integrations; the taxonomy editor loads the whole tree |
| `payment_payment` | Single payment by id; the payments tab shows payments in full in its list |
