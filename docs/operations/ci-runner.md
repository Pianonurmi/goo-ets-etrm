# CI runners: hosted, or a self-hosted runner

The repository is public, so the workflows in `.github/workflows/` run on GitHub's hosted runners, which are free
for public repositories (ADR 0041). This page also describes a self-hosted runner in the homelab, for when the
repository is private again: self-hosted runners use no Actions minutes, even for a private repository.

## Choosing the runner
Every job has:
```yaml
runs-on: ${{ fromJSON(vars.CI_RUNNER || '"ubuntu-latest"') }}
```
- **Default:** GitHub's hosted `ubuntu-latest`.
- **Self-hosted, private repository only:** set the repository variable `CI_RUNNER` to
  `["self-hosted","linux","x64","etrm"]` to run on the homelab runner below. The steps work on both: those that
  need root (Playwright's system libraries, the PostgreSQL 17 client in the DR drill) run only on hosted runners,
  and the self-hosted runner has them from provisioning.
- **Unset the variable** to go back to hosted runners. Do this before the repository is made public (see Security).

## The machine
- **Hardware:** Ubuntu 24.04 with Docker Engine, x86-64. At least 2 vCPU and 8 GB RAM (plus a few GB of swap),
  like GitHub's hosted runner for a private repository, and 40 GB disk: more than a hosted runner's, because a
  persistent runner keeps its caches and images. 4 vCPU roughly halves the build time.
  - **Memory:** Gradle runs with 3 GB and the Kotlin daemon with 2 GB. Tests start PostgreSQL (the service
    container), Kafka (Testcontainers), the application and Chromium.
  - **Disk:** images, the Gradle and npm caches, and Playwright's browsers accumulate.
- **Its own VM, not the demo VM** ([homelab.md](../deploy/homelab.md)). A build uses every core for minutes, and the
  runner runs code from every branch pushed.
- **Network:** outbound only. The runner polls GitHub over HTTPS; nothing connects to it.

## Setting it up
1. Install Docker Engine (<https://docs.docker.com/engine/install/ubuntu/>).
2. Copy `ops/runner/` to the machine and run `sudo ./provision.sh`. It is safe to run again, and should be after a
   Playwright upgrade (`PLAYWRIGHT_VERSION=x.y.z sudo -E ./provision.sh`). It:
   - checks Docker;
   - installs the PostgreSQL 17 client (PGDG) and Chromium's system libraries for the Playwright version in
     `apps/web/package-lock.json`, using a checksum-verified Node 22 that is thrown away afterwards;
   - creates the user `runner` (no sudo, in the `docker` group);
   - enables a weekly Docker cleanup.
3. On GitHub: *Settings → Actions → Runners → New self-hosted runner*, and copy the registration token. It is valid
   for one hour.
4. `sudo ./install-runner.sh https://github.com/Pianonurmi/goo-ets-etrm <token>`. It:
   - downloads the latest runner release and checks it against the SHA-256 GitHub records for it
     (`RUNNER_VERSION=2.x.y` pins a version; `RUNNER_SHA256` overrides the digest);
   - registers the runner with the label `etrm` (name: the host name; `--replace` takes over a runner of that
     name);
   - installs it as a systemd service.
5. The runner shows as *Idle* under *Settings → Actions → Runners*. Push a commit, or re-run a workflow, to check.

## Maintenance
- **Updates:** the runner updates itself when GitHub releases a new version. Ubuntu updates are yours
  (`unattended-upgrades` is a good default).
- **Disk:**
  - `docker-prune.timer` removes images, containers and build cache unused for a week, every Sunday at 03:30
    (`systemctl list-timers docker-prune.timer`).
  - Gradle cleans its own caches.
  - `df -h` now and then.
- **One job at a time:** one runner runs one job at a time, so CI's two jobs (chart, build) and the scheduled
  workflows queue. The PostgreSQL service takes a free host port, so a second runner on the same machine (another
  `install-runner.sh` with `RUNNER_NAME=...` and its own user) or on a second VM can run jobs in parallel. Each job
  also starts the application on 8080 (ZAP, load, DR drill), so give each runner its own machine.
- **Logs:** `journalctl -u 'actions.runner.*'`, and `_diag/` under `~runner/actions-runner`.
- **Removing it:** `cd ~runner/actions-runner && sudo ./svc.sh uninstall`, then `sudo -u runner ./config.sh
  remove --token <removal token>` (*Settings → Actions → Runners → the runner → Remove*).

## Security
- **Never make the repository public while it uses a persistent self-hosted runner.** A pull request from a fork
  would run its code on the homelab machine. If the repository goes public, either move back to hosted runners
  (`CI_RUNNER`), or use ephemeral runners and require approval for outside contributors.
- **Who can reach the runner:** the runner executes what is pushed to the repository, including Dependabot's
  branches. Only people with write access can push.
- **The `runner` user:**
  - It has no sudo.
  - Its membership of `docker` is close to root on that machine, which is why the runner gets a VM of its own.
- **Tokens:**
  - Set *Settings → Actions → General → Workflow permissions* to read-only. Jobs then get read-only tokens unless
    they ask for more; only the CI build job asks, for `packages: write`, to push the image from `main`.
  - The `GITHUB_TOKEN` a job receives expires with the job.
- **Storage:** artifacts and GHCR images count against the account's storage, which runs out separately from
  minutes. The Playwright report is uploaded only when a job fails, and reports are kept seven days. Prune old GHCR
  versions now and then (*Packages → goo-ets-etrm → Package settings*).
