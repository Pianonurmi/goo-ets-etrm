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
  - The build fails on a critical vulnerability that has a fix.
  - Accepted findings go into `.trivyignore`, one CVE per line, with the reason and a review date.
- **Dependencies (weekly, `security.yml`):**
  - The OWASP dependency check scans the jar and the web lockfile against the NVD.
  - It fails at CVSS 9 or higher.
  - It needs the repository secret `NVD_API_KEY`; without it the job says so and stops.
- **Web:** `npm audit --omit dev` is clean at the time of writing; Dependabot raises updates.

## Updates

Dependabot raises weekly updates for Gradle (Spring and Kotlin grouped), npm (React, Mantine and development tooling
grouped), the runtime image (`ops/Dockerfile`) and GitHub Actions.
