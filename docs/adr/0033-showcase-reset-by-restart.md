# ADR 0033: Showcase reset by restart

- Status: accepted
- Date: 2026-10-11

## Context
The public showcase (ADR 0032) is shared: every visitor can change the data, as any persona. It has to return to the
example portfolio every night and on an administrator's request. The data goes through the same API, checks, events,
approvals and hash chains as real work, and the seeder builds it through the API (M2).

Wiping the database inside a running application is unsafe: caches, scheduled jobs mid-run, event publications in
flight, open sessions and in-memory state (the seed token, sign-in throttle) would all see the data disappear under
them.

## Decision
- **Every start of the showcase wipes its database.** `ShowcaseWipe`, a `MigrationHook` run by `ModularMigrations`
  before Flyway, drops every `etrm_*` schema; the migrations and the demo seeder then rebuild everything.
- **Guard.** The wipe refuses to run unless `current_database()` equals `etrm.showcase.database`
  (`ETRM_SHOWCASE_DATABASE`), and the start fails if the name is not set. A showcase pointed at another database by
  mistake cannot wipe it. `etrm.showcase.wipe-on-start: false` turns the wipe off (tests).
- **A reset is a restart.** The service ends the process with exit code 75 (temporary failure) after closing the
  Spring context; Docker's `restart: unless-stopped` starts it again, which wipes and seeds.
- **Nightly** at 04:15 Europe/Helsinki (`etrm.showcase.reset-cron`, `reset-zone`), under a ShedLock lock.
- **Administrator's reset:** `POST /api/v1/showcase/reset {reason}` needs the ADMIN persona and a reason, and is
  refused for ten minutes after a start (`reset-cooldown`), so visitors cannot keep the demo restarting. Outside the
  showcase the endpoint answers 404. The UI (Administration → Demo) asks for the reason and for "RESET" typed out, then
  follows the restart through `ui-config` and returns to the sign-in page.
- `ui-config` reports the next nightly reset and when a reset is allowed (`showcase`), and the seed progress
  (`demoSeed`, M2).

## Consequences
- Any crash or redeploy also resets the demo. Visitors are told the data is reset nightly; nothing in the showcase is
  kept.
- The demo is unavailable while it reseeds (about a minute); readiness is down and Caddy shows a maintenance page.
- Sessions do not survive a reset; visitors sign in again.
- `ShowcaseWipeIT` checks the guard and the wipe on a database of its own.
