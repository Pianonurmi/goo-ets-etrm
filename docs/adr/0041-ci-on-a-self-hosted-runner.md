# ADR 0041: CI on a self-hosted runner

- Status: accepted
- Date: 2026-10-12

## Context
The repository is private, and the account's GitHub Actions minutes ran out: every run failed within seconds,
before any job started. The rule "push to `main` only after a green CI run" then blocks all work on `main`.

CI does more than run tests: the GHCR image the demos pull (`:main`), SBOMs, the Trivy gate, and weekly security,
ZAP, DR-drill and screenshot workflows. Moving to another CI system would lose the checks on GitHub, GHCR
publishing with the job token, and Dependabot's integration.

## Decision
- **Where jobs run:** a self-hosted GitHub Actions runner on a Linux VM with Docker in the homelab, labelled `etrm`.
  GitHub still schedules the runs and hosts checks, artifacts and the registry; the jobs execute at home and use no
  minutes.
- **Switchable runner:** every job's `runs-on` is `fromJSON(vars.CI_RUNNER || '["self-hosted","linux","x64","etrm"]')`.
  Setting the repository variable `CI_RUNNER` to `"ubuntu-latest"` switches back to hosted runners without a code
  change.
- **Persistent and non-root runner:**
  - **Provisioned once** (`ops/runner/provision.sh`): what needs root comes with the machine (the PostgreSQL 17
    client, Chromium's system libraries). The steps that installed them with sudo now do so only on hosted runners.
  - **Database port:** PostgreSQL service containers take a free host port, and every job reads it from the
    `job.services` context.
  - **Downloads:** downloaded tools go to the job's temporary directory and are checked against published
    checksums.
  - **Cleanup:** a weekly timer removes Docker images and build cache older than a week.
- **Install:** `ops/runner/install-runner.sh` installs the runner as a systemd service, checked against the SHA-256
  digest GitHub records for the release. The runner updates itself.

## Consequences
- **No minutes:** CI no longer depends on the minutes balance. Storage for artifacts and packages still counts, so
  the Playwright report is uploaded only on failure.
- **Queueing:** one runner runs one job at a time, so a push's chart and build jobs and the weekly workflows queue.
  A second runner on its own machine removes that.
- **Upkeep:** the machine is the owner's to maintain: Ubuntu updates, disk, and Playwright's system libraries after
  an upgrade (re-run `provision.sh`).
- **Never public:** the repository must not become public while it uses a persistent self-hosted runner (fork pull
  requests would run on the homelab). See `docs/operations/ci-runner.md`.
- **Docker for Kafka:** `KafkaExternalizationIT` still needs Docker, which the runner provides (ADR 0029).
