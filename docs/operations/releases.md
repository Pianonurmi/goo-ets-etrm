# Versions, releases and image tags

Provena uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html) (ADR 0042). Each release is a git tag
`vX.Y.Z`, a GitHub release and a set of image tags. The changes of every version are in
[CHANGELOG.md](../../CHANGELOG.md).

## The version number

- **0.x while the system is pre-production.** Each minor version is one increment of the [roadmap](../roadmap.md)
  (0.9.0 is increment 9). 1.0.0 follows once the [path to production](../roadmap.md) is clear: the external
  penetration test, the production DR drill and the outside sign-offs.
- **Patch (X.Y.Z+1):** fixes and dependency updates, with no change to a contract.
- **Minor (X.Y+1.0):** new features. Contracts change only compatibly.
- **Major (X+1.0.0), from 1.0 on:** an incompatible change to a contract.
- **Contracts:**
  - the REST API (the OpenAPI document, ADR 0027);
  - the JSON of events sent to Kafka (ADR 0029);
  - the environment variables and Helm values a deployment sets;
  - the database. Its migrations are forward-only, so a version can be upgraded to but not rolled back to in place:
    restore a backup instead ([disaster recovery](disaster-recovery.md)).

## Where the version is written

| Place | Between releases | At a release |
|---|---|---|
| `gradle.properties` `version` | `X.Y.Z-SNAPSHOT` (the next release) | `X.Y.Z` |
| `apps/web/package.json` `version` | the last release | `X.Y.Z` |
| `ops/helm/etrm/Chart.yaml` `version` and `appVersion` | the last release (the chart deploys a released image) | `X.Y.Z` |
| `CHANGELOG.md` | `## [Unreleased]` | `## [X.Y.Z] - YYYY-MM-DD` |

- **Checked by CI:** on a tag, CI runs `ops/release/check-version.sh X.Y.Z`, which fails the release when any of
  these disagrees with the tag.
- **Visible in the running application:** the build info (`/actuator/info`), the `version` of
  `GET /api/v1/ui-config`, and the image label `org.opencontainers.image.version`.

## Image tags

CI publishes `ghcr.io/pianonurmi/goo-ets-etrm` only after the full build, the end-to-end tests and the vulnerability
gate pass (`ops/release/image-tags.sh`):

| Pushed | Image tags | Use it for |
|---|---|---|
| A commit to `main` | `main`, `<commit sha>` | The newest tested build: development and the demo |
| Tag `vX.Y.Z` | `X.Y.Z`, `X.Y`, `latest`, `<commit sha>`; from 1.0 on also `X` | Releases |
| Tag `vX.Y.Z-rc.N` (pre-release) | `X.Y.Z-rc.N`, `<commit sha>` | Trying a release candidate; no floating tag moves |

- **Image labels:** every image carries the OCI labels `version`, `revision` (the commit), `created`, `source` and
  `licenses`. `docker inspect` shows what is running.
- **Choosing a tag:**
  - **`X.Y.Z`:** production pins it and upgrades on purpose.
  - **`X.Y`:** a test environment that should take patch releases automatically.
  - **`latest`:** follows the newest final release.
  - **`main`:** follows every green commit.

## Updating a deployment

- **Docker Compose** (`ops/demo`, [VPS](../deploy/vps.md), [homelab](../deploy/homelab.md)):
  1. Set `ETRM_IMAGE=ghcr.io/pianonurmi/goo-ets-etrm:0.9.0` (or `:0.9`) in `.env`.
  2. Run `docker compose pull app && docker compose up -d app`.
  3. To roll back, set the previous version and restore the database backup taken before the upgrade.
- **Helm** ([Kubernetes](kubernetes.md)):
  - `helm upgrade --install etrm ops/helm/etrm` deploys the chart's `appVersion` image.
  - `--set image.tag=X.Y.Z` deploys another version.
  - The chart in the release's tag matches its image.

## Cutting a release

1. **On the development branch**, prepare the release:
   - set the version `X.Y.Z` in `gradle.properties`, `apps/web/package.json` (`npm version X.Y.Z --no-git-tag-version`
     in `apps/web`) and `ops/helm/etrm/Chart.yaml`;
   - turn `## [Unreleased]` in `CHANGELOG.md` into `## [X.Y.Z] - <today>` and add the compare link;
   - run `ops/release/check-version.sh X.Y.Z`.
2. Commit "Release X.Y.Z". `main` follows once CI is green.
3. Tag the commit. Either way works:
   - **Without a clone:** run the **Release** workflow from GitHub's Actions tab with the version `X.Y.Z` (and `ref`,
     default `main`; give the release commit's sha once `main` has moved on). It checks the version, tags the commit
     `vX.Y.Z` as the Actions bot, and calls CI for it.
   - **From a clone:** `git tag -a vX.Y.Z -m "Provena X.Y.Z" && git push origin vX.Y.Z`. The pushed tag starts CI.
4. **CI on the tag:**
   - checks the version, builds, tests and scans as on `main`;
   - publishes the image tags;
   - creates the GitHub release. Its body is the changelog section and the image to pull; the jar
     (`provena-X.Y.Z.jar`) and the SBOMs are attached.
5. **Start the next version:**
   - set `gradle.properties` to the next minor with `-SNAPSHOT`;
   - add an empty `## [Unreleased]` to the changelog;
   - commit "Start X.Y+1.0".

**A patch to an older line:**
1. Branch `release/X.Y` from its tag.
2. Fix and release `X.Y.Z+1` from that branch; `X.Y` then moves to the patch.
3. Carry the fix forward to `main`.

**History:** tags, GitHub releases and images start with 0.9.0. Increments 1 to 8 are listed in the changelog as
0.1.0 to 0.8.0 without tags: GitHub does not let the workflow token create a ref on those older commits, because their
workflow files differ from today's, and tags from this repository's CI cannot be made another way.

**A release made by hand in the GitHub UI** (a new tag on the release form) also starts CI. CI then fills that
release's notes and attaches the assets, instead of creating a new one.
