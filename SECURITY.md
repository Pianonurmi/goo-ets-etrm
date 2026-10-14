# Security

## Reporting a vulnerability

Please report security problems privately through GitHub's **Report a vulnerability** form on this repository's
Security tab, not in a public issue. Include the request and response, the version (`/api/v1/ui-config` shows it in
the UI footer) and the steps to reproduce. You will get an answer within five working days.

## Supported versions

Per [ADR 0051](docs/adr/0051-version-and-support-policy.md): the latest minor release, and the previous minor for 90
days after its successor's release. Security fixes reach both lines as patch versions (`X.Y.Z+1`), listed under
**Security** in `CHANGELOG.md` with what was exposed and since which version.

| Version | Supported |
|---|---|
| 0.14.x | yes |
| 0.13.x | security fixes until 90 days after the 0.14.0 release |
| older | no |

## What is in place

Authentication and authorisation, headers, supply-chain scanning and the penetration-test brief are described in
[docs/security/penetration-test.md](docs/security/penetration-test.md); the last whole-system review is
[docs/reviews/2026-10-enterprise-readiness.md](docs/reviews/2026-10-enterprise-readiness.md).
