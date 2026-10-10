# Screens

Each screen lists the operations it calls (operation ids from `apps/api/openapi/etrm-api.json`) and who can act
there. Read-only parts are open to every signed-in user unless noted. The API enforces roles, four eyes and
legal-entity scope; the UI only mirrors them.

| Screen | Route | Operations | Acting roles |
|---|---|---|---|
| Home | `/` | `ui_me`, `referenceData_legalEntities`, `workflow_inbox` (actionable, first five), `workflow_counts` | everyone |
| Approval inbox | `/inbox` | `workflow_inbox` (cursor paging, filters in the URL), `workflow_taskTypes`, `workflow_counts`, `workflow_bulk` | candidates of each task type |
| Task detail | `/inbox/:taskId` | `workflow_task`, `workflow_approve`, `workflow_reject`, `workflow_acknowledge`, `workflow_claim`, `workflow_release`, `workflow_assign`, `workflow_comment` | candidates; never the requester when the type is four-eyes |

## Inbox rules mirrored in the UI (`features/inbox/model/decisions.ts`)

- A task can be decided while it is open, by a user holding one of its candidate roles (or ADMIN). With four eyes
  (the default), the requester may not decide.
- An assignee only marks who is working on a task. Any candidate may still decide it, as in `WorkflowService`.
- The task type's `reasonRequiredFor` decides when a reason is mandatory.
- Bulk decisions apply to the selected tasks the user may decide. The server reports each refusal.
- Subjects link to their screens once those exist (`model/subjects.ts`).
