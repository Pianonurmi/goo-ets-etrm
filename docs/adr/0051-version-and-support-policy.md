# ADR 0051: Versions and support

- Status: accepted
- Date: 2026-10-14
- Amends: ADR 0042

## Context
ADR 0042 set semantic versions with one minor per increment in the 0.x line, and patch branches (`release/X.Y`). The
first patches (0.13.1 for security, 0.13.2 for accounting) showed that a customer needs to know which lines receive
fixes, and for how long.

## Decision
- **Supported lines**: the latest minor, and the previous minor for 90 days after its successor's release. Security
  fixes reach both; other fixes reach the latest minor, and the previous one when they correct figures in the books.
- **A patch release is cut from `release/X.Y`** by pull request, tagged by the Release workflow, and carried forward
  to `main` in its own pull request (`docs/operations/releases.md`).
- **Every release is upgradeable from the previous minor** without manual steps beyond those its changelog names,
  because migrations follow ADR 0048.
- **The changelog states exposure**: a security fix says what was exposed and since which version.
- **1.0** is cut after a production pilot has run a full month end and the external penetration test has been
  closed; until then the 0.x line may change APIs between minors, with the change named in the changelog.

## Consequences
- At most two lines need patches at any time.
- `SECURITY.md` lists the supported versions and is updated with every minor release.
