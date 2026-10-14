## What and why

<!-- The change in a few sentences, and the issue, plan milestone or review finding it answers. -->

## How it was checked

- [ ] `./gradlew build` (lint, detekt, codegen, tests, coverage rule)
- [ ] `./gradlew :apps:web:check` when the web UI changed
- [ ] Playwright (`npm run e2e`) when a screen or a REST guard changed
- [ ] `./gradlew :apps:api:updateOpenApiSnapshot` committed when a controller or view changed

## Risk

- [ ] Database migration: additive and safe for the previous release running beside it (expand, then contract)
- [ ] Money, postings or positions: a test shows the figures, including rounding and a second currency
- [ ] Access: role and legal-entity checks for every new or changed endpoint
- [ ] `CHANGELOG.md` under `## [Unreleased]` for anything a user or operator notices
