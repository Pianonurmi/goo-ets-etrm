# ADR 0011: The approval inbox as a leaf module with a task handler SPI

- Status: accepted
- Date: 2026-10-10

## Context
In increment 1 the workflow module listened to trade and allocation events and called their commands when a task
was decided, so it depended on both. Increment 2 adds task types in registry, inventory, trade, settlement and
reference data (instruction approvals, reconciliation breaks, confirmation breaks, invoice approvals, bank detail
changes). Several of those modules need to open tasks and react to decisions, which with the old design would
create dependency cycles (for example reference data → workflow → reference data).

## Decision
- `workflow` depends on nothing but `platform`. Owning modules depend on `workflow :: api`.
- Each owning module registers a `TaskHandler`: a `TaskTypeDefinition` (candidate roles, four-eyes, allowed decisions, decisions needing a reason, default SLA, escalation roles, default priority) and a synchronous `onDecision`.
- The owning module opens tasks (`WorkflowCommand.open`) from its own events and closes them with `closeFor` when the work is finished elsewhere.
- Deciding a task: workflow checks the rules, records the decision and its history, then calls the handler in the same transaction. If the handler throws, the decision is vetoed and nothing is kept. A handler may report the decision as superseded (for example a stale allocation plan); the task is then cancelled with that reason.
- Workflow never computes business-day deadlines: owners pass `dueAt` when calendar rules matter, otherwise the type's SLA applies.
- Legal-entity scope: tasks are visible to actors whose scope contains the task's entity, or who hold all-entity access. In production, an identity without the entity claim sees no entity data; `*` must be granted explicitly.

## Consequences
- Adding a task type needs no change in workflow. The registered types are stored in `task_type_definition` at start-up for reporting.
- Bulk decisions run each decision in its own savepoint, so one refusal does not undo the others.
- Inbox pages use keyset cursors, so paging stays stable while tasks are opened and closed.
