# Screens

Each screen lists the operations it calls (operation ids from `apps/api/openapi/etrm-api.json`) and who can act
there. Read-only parts are open to every signed-in user unless noted. The API enforces roles, four eyes and
legal-entity scope; the UI only mirrors them.

| Screen | Route | Operations | Acting roles |
|---|---|---|---|
| Home | `/` | `ui_me`, `referenceData_legalEntities`, `workflow_inbox` (actionable, first five), `workflow_counts` | everyone |
| Approval inbox | `/inbox` | `workflow_inbox` (cursor paging, filters in the URL), `workflow_taskTypes`, `workflow_counts`, `workflow_bulk` | candidates of each task type |
| Trade blotter | `/trades` | `trade_search` (one query per page, keyset cursor, filters and `q` in the URL), `referenceData_counterparties`, `referenceData_books` | everyone in scope |
| Trade capture and amendment | `/trades/new`, `/trades/:tradeId/amend` | `trade_capture`, `trade_amend`, criteria builder, `referenceData_registryAccounts`, `product_allowances` | TRADER, MIDDLE_OFFICE |
| Trade detail | `/trades/:tradeId` | `trade_trade`, `trade_versions` (diff per version), `confirmation_forTrade`, `valuation_tradeMtm`, `trade_confirm`, `trade_cancel`, link to the approval task | confirm: MIDDLE_OFFICE, BACK_OFFICE; amend/cancel: TRADER, MIDDLE_OFFICE |
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
| Lot detail | `/inventory/lots/:lotId` | `inventory_lot`, `inventory_history`, `inventory_asOf`, `inventory_split`, `inventory_cancel`, `inventory_withdraw` | operations: BACK_OFFICE, REGISTRY_OPERATOR |
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
