# goo-ets-etrm

ETRM for Guarantees of Origin (GoO) and EU ETS allowances. Kotlin, Spring Boot 4, Spring Modulith, PostgreSQL,
jOOQ, Flyway, Gradle. The contributor handbook below is the reference for building, testing and every convention;
this file adds only what is specific to coding assistants.

@docs/contributing.md

## Working with sub-agents

- Consider delegating self-contained tasks to sub-agents, and run independent ones in parallel, each in its own
  worktree (`isolation: worktree`).
- Choose the model by the task, to keep token costs down:
  - a small model (`haiku`) for searches, inventories and mechanical edits;
  - `sonnet` for well-specified implementation;
  - the main model only for design, hard debugging and review.
- Give each agent a complete brief: the handbook's conventions, the files it must not touch, the test ids to keep, the
  checks to run (tsc, eslint, prettier, Vitest; Gradle and Playwright only when nothing else uses the database), and
  what to report. An agent reports gaps it finds instead of widening its scope.
- Review every agent's result with the main model before merging it:
  - read the diff;
  - run the checks and the affected tests (Playwright for UI work);
  - look at the screenshots of changed screens;
  - fix or send back what does not meet the conventions.

## Assistant specifics

- Commit as the project identity given in the handbook, never as yourself or a person; put no model identifier in
  commits, pull requests, code or documents.
- Where this file and the handbook differ, the handbook wins and this file is corrected.
