# ADR 0028: A role-aware UI keyed by operation id

- Status: accepted
- Date: 2026-10-11

## Context
The services decide who may do what. They check roles, legal-entity scope and four eyes, and they refuse
requester = approver. The UI should not offer an action a user cannot take, and should not hide screens a user
needs. There are three ways to make the UI aware of permissions:
1. fetch a permission matrix from the server;
2. try each action and handle the 403;
3. keep a table in the client.

A server matrix would need a new endpoint and a way to describe each service check. The service checks are code
(`requireRole`, `requireEntity`, `SegregationOfDutiesException`), not data. Trying and failing gives a poor
experience for actions that are plainly not the user's.

## Decision
- `shared/auth/permissions.ts` maps an **operation id** (ADR 0027) to the roles that may call it. It mirrors the
  service's `requireRole` and nothing more. ADMIN may call everything. An operation that is not listed is open to
  every signed-in user.
- The table is typed with `Partial<Record<OperationId, readonly Role[]>>`. A renamed or removed operation breaks
  the web typecheck, so the table cannot keep entries for operations that no longer exist.
- `<Can op="…">` renders an action only for permitted users. `useCan(op)` decides whether to run a query whose
  endpoint is role-restricted, such as the GL exports or the hard-close checklist. Navigation items carry the reader
  roles of their screen.
- What the table cannot know stays on the server, and the UI shows the server's answer:
  - **Entity scope.** The entity selector offers only entities in the user's scope, and the API refuses others
    (`EntityScopeInterceptor`).
  - **Four eyes.** The inbox computes "you requested this" from the task (`decisions.ts`), and the service still
    refuses.
  - **State rules** (a closed period, a hard-close blocker, an unbalanced journal) come back as problems and are
    shown next to the action (`ProblemAlert`).
- A role change in a service is a change in two places: the service and `permissions.ts`. The screens document
  (`docs/ui/screens.md`) lists the acting roles per screen, so a review covers both.

## Consequences
- No extra endpoint and no runtime cost. Permissions are visible in one file.
- Drift is possible when a service's roles change and the table does not. The API stays authoritative, so drift
  shows a button that then fails with 403, or hides one a user could have used. It never grants anything.
- If the rules become data (configurable roles per entity), a server-side matrix keyed by the same operation ids
  can replace the table without changing `<Can>` call sites.

## Update (increment 15)

The table is no longer written by hand. Each REST handler whose service checks roles names them in `@AllowedRoles`
(platform `security`); the OpenAPI document publishes them as `x-roles` on the operation, and `npm run gen:api`
generates `shared/auth/permissions.generated.ts` from the snapshot. A role change is now one change in the backend
(the service and the annotation next to it) and a snapshot update; the web follows on its next build.
