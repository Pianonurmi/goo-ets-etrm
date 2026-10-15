# Supply chain

## Bills of materials

| What | How | Where |
|---|---|---|
| Backend (the jar's runtime classpath) | CycloneDX Gradle plugin, `./gradlew :apps:api:cyclonedxDirectBom` | `apps/api/build/reports/sbom/etrm-backend.cdx.json`; also in the jar (`META-INF/sbom/application.cdx.json`), served by `/actuator/sbom` |
| Web bundle (production dependencies) | `@cyclonedx/cyclonedx-npm`, `npm run sbom` in `apps/web` | `apps/web/build/sbom/etrm-web.cdx.json` |
| Container image | Trivy, CycloneDX output | CI artifact `sbom` (`etrm-image.cdx.json`) |

CI publishes all three as the `sbom` artifact (kept 90 days) for every build. The workflows run on GitHub's hosted
runners, with a self-hosted runner as the fallback for a private repository (ADR 0041, [ci-runner.md](ci-runner.md));
the tools a job downloads (kubeconform, the runner itself) are checked against published checksums.

## Vulnerability gates

- **Image (every build):**
  - Trivy reports critical and high vulnerabilities.
  - The build fails on a critical or high vulnerability that has a fix (since 0.14.0; critical only before).
  - Accepted findings go into `.trivyignore`, one CVE per line, with the reason and a review date.
- **Dependencies (weekly, `security.yml`):**
  - The OWASP dependency check scans the jar and the web lockfile against the NVD.
  - It fails at CVSS 7 or higher.
  - It needs the repository secret `NVD_API_KEY`; without it the job fails, so a missing key cannot pass as a clean scan.
- **Web:** `npm audit --omit dev` is clean at the time of writing; Dependabot raises updates.

## Pinning

- **Actions** are pinned to a commit SHA with the version as a comment, and base images (`ops/Dockerfile`, the CI
  PostgreSQL service) to a digest. Dependabot moves both.
- **Gradle dependencies** have exact versions in `gradle/libs.versions.toml`, aligned by BOMs (Spring Boot, Jackson 2
  and 3) and by `constraints` for transitive fixes (`lz4-java`). Every resolved configuration is **locked**
  (`gradle.lockfile` in each project, `settings-gradle.lockfile`, `build-logic/gradle.lockfile`), so the transitive
  graph cannot move without a diff, and **`gradle/verification-metadata.xml`** holds the SHA-256 of every artifact
  and POM the build downloads, plugins included; Gradle refuses an artifact whose checksum differs or is missing.
  Sources and javadoc jars, which only IDEs fetch, are trusted without a checksum.
- **The Gradle wrapper**: `gradle/actions/setup-gradle` validates the wrapper jar against Gradle's published checksums
  on every run, and CI's "Gradle distribution checksum" step compares `distributionSha256Sum` in
  `gradle/wrapper/gradle-wrapper.properties` with the checksum Gradle publishes for the distribution.
- **Web dependencies** are locked by `apps/web/package-lock.json` (with integrity hashes) and installed with `npm ci`.

To change a Gradle dependency by hand, refresh the locks and the checksums together, with the code-generation database
up (assembling generates the jOOQ sources):

```bash
ops/release/refresh-dependency-locks.sh
```

The script empties the components first, which drops the versions the change replaced; the configuration block stays.
Besides resolving every configuration, it runs the tasks that resolve their own at execution time (the compilers,
detekt, ktlint, the SBOM): without them about a tenth of the artifacts a build fetches would be missing. Review the
diff of `verification-metadata.xml` like code: a checksum that changes for a version that did not is a reason to stop.

## Updates

Dependabot raises weekly updates for Gradle (Spring and Kotlin grouped), npm (React, Mantine and development tooling
grouped), the runtime image (`ops/Dockerfile`) and GitHub Actions (all actions in one group). Each ecosystem may keep
ten PRs open, so a backlog does not hide updates.

Dependabot does not regenerate the Gradle lock files or the verification metadata, and its npm lock files leave out
optional peers (Vitest's `msw`), so `npm ci` refuses them. The **Dependabot lock refresh** workflow
(`.github/workflows/dependabot-refresh.yml`) runs on each Dependabot pull request for Gradle or npm: one job
regenerates the files with a read-only token, a second job takes only lock files from it, pushes them to the
Dependabot branch as "Refresh lock files and verification metadata" and starts CI on that commit (a push with the
workflow token starts no workflow by itself, so CI is started through `workflow_dispatch`). Once a branch carries that
commit, Dependabot no longer rebases it; comment `@dependabot recreate` to have it start over, and the refresh runs
again.

An update lands as a commit of its own on the development branch, checked by the full build and the end-to-end tests,
and reaches `main` once CI is green; Dependabot then closes its PR. A major version that the tooling cannot take yet is
held back with an `ignore` rule in `.github/dependabot.yml`, and the rule says why:

| Held back | Why | Lift when |
|---|---|---|
| TypeScript majors (6, 7) | typescript-eslint 8 supports TypeScript below 6.1, openapi-typescript 7 only 5.x | Both declare support |
| `@types/node` majors above 22 | The runtime and CI are Node 22; newer types describe APIs it does not have | The build moves to a newer Node line |
| PostgreSQL majors in `ops/demo` | A new major cannot open the previous major's data directory, so the demo would not start | The database is upgraded by hand (dump and restore, or `pg_upgrade`), with CI and CloudNativePG on the same major |

`eslint-plugin-jsx-a11y` 6.10 still declares ESLint 9 as its peer; an npm override lets it run under ESLint 10, which it
does correctly. Remove the override when the plugin declares ESLint 10.
