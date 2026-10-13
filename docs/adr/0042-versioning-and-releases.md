# ADR 0042: Semantic versions, release tags and versioned images

- Status: accepted
- Date: 2026-10-12

## Context
Until now every build carried the version `0.1.0-SNAPSHOT`, and CI published the image only as `main` and the commit
sha.
- **No upgrade path:** a deployment could follow `main` or pin a sha, but could not say "the release of increment 8"
  or "every patch of 0.9".
- **No traceability:** nothing tied a running system to a release with its notes and its SBOMs.

An enterprise deployment needs to answer three questions:
- which version runs where;
- what changed between two versions;
- how to upgrade or go back on purpose.

## Decision
- **Semantic Versioning, 0.x until production.** Each minor version is one roadmap increment. 1.0.0 follows the path
  to production.
- **The contracts a version number speaks for:** the REST API, the event JSON, the environment variables and Helm
  values, and the forward-only database migrations.
- **One source:** `gradle.properties` holds the version; it is `X.Y.Z-SNAPSHOT` between releases. The web package,
  the Helm chart and `CHANGELOG.md` are set to the release version when it is cut.
- **A check against drift:** on a tag, `ops/release/check-version.sh` fails the build when any of them disagrees
  with the tag.
- **A release is a tag `vX.Y.Z`.** It is pushed from a clone, or created by the **Release** workflow from the
  Actions tab, which needs no clone and calls CI itself (a tag pushed with the workflow token starts no workflow).
  The same CI workflow as `main` builds it, tests it and scans the image, then:
  - **Publishes floating image tags.** `X.Y.Z`, `X.Y`, `latest` and the sha; from 1.0 also `X`. A pre-release
    (`-rc.N`) gets only its own tag and the sha.
  - **Creates the GitHub release** from the changelog section, with the jar and the SBOMs attached.
- **OCI labels:** images carry `version`, `revision`, `created`, `source` and `licenses`.
- **Older lines get patches from a `release/X.Y` branch.**
- **History:** `v0.1.0` to `v0.8.0` are tags on the commits that closed increments 1 to 8, without images or
  GitHub releases.

## Consequences
- **Deployments choose their track:**
  - production pins `X.Y.Z`;
  - a test system follows `X.Y`;
  - the demo can follow `main` or `latest`.
- **Rollback:** going back means the previous image plus the database backup taken before the upgrade, because
  migrations are forward-only.
- **Release overhead:** a release is two small commits (release, start next) and a tag; CI does the rest. The
  procedure is in [releases](../operations/releases.md).
- **Two version kinds:** the chart's `version` and `appVersion` move together, at releases only, so the chart in
  a release tag always deploys that release's image.
