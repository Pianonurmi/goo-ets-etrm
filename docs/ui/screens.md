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
