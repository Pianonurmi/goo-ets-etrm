# ADR 0034: Provena design system and app shell

- Status: accepted
- Date: 2026-10-11

## Context
The web UI (ADR 0025) used Mantine's defaults: the system font, the default teal and greys, no brand, a plain home
page and spinners. For the public showcase (increment 7) the suite should look and work like a current product in
the spirit of Linear, Stripe and Vercel: calm, dense, consistent. The proposal was reviewed on a design board and
approved.

## Decision
- **Name and mark.** The product is called **Provena** (from *provenance*). The mark is three ledger lines
  shortening to a seal: certificates as number ranges, closed by an attested origin. It is an inline SVG
  (`app/brand/Logo.tsx`, `public/favicon.svg`).
- **Tokens** (`app/tokens.ts`), defined as CSS variables `--etrm-*` for both schemes by the theme's resolver:
  - one accent, deep teal `#0E6E7E` (light) and `#3BB6C9` for text on dark;
  - slate neutrals (canvas `#F8FAFC`, lines `#E2E8F0`, muted `#475569`, ink `#0F172A`) and a tuned dark scheme
    (canvas `#0B1220`, surface `#111A2B`), not an inversion;
  - meaning colours: gain, loss, warning, info; buy blue and sell orange;
  - every text colour at WCAG AA in both schemes (`contrast.test.ts`, axe in Playwright).
- **Type.** Geist and Geist Mono (SIL Open Font License), self-hosted through `@fontsource-variable` so the
  content security policy stays `font-src 'self'`. Tabular numerals for every figure (grids, tables, `.etrm-figure`).
- **Shape.** Radius 8 px for controls and 12 px for cards, soft shadows, a visible focus ring in the accent, motion
  only where it explains and none under `prefers-reduced-motion`.
- **Status colours.** One map for every lifecycle (`StatusBadge`): grey draft or ended, amber waiting for someone,
  blue in progress, green done, red refused or broken. Labels are sentence case.
- **Shared components** (`shared/components`): `PageHeader` (breadcrumbs, title, status, actions), `KpiCard` with
  a sparkline, `StatusBadge`, `Money`/`Quantity`/`Side`, `EmptyState`, skeletons, `DetailLayout` with a fact grid.
  Screens move to them in the design pass (M9).
- **App shell.** A collapsible sidebar with the logo, icons and section labels; a top bar with the legal entity, a
  search field that opens the command palette, the environment badge, the inbox bell, help and shortcuts, display
  settings (colour scheme and comfortable or compact density, kept per browser) and the persona or user menu.
  Keyboard: `Ctrl K` or `/` search, `?` shortcuts, `[` sidebar, `Shift D` density, `Shift L` light or dark.
- **AG Grid** uses the theming API with the same colours, 40 px rows (32 px compact) and no column borders.
- **Home.** A role-aware dashboard from one call, `GET /api/v1/home/summary?legalEntityId=` (`home` package in
  `apps/api`): open tasks, GoO coverage, mark-to-market with its history, EUA net position, registry breaks, overdue
  invoices, limit breaches, coverage by delivery month, ETS and month-end deadlines, and recent activity. Each section
  is present only for roles that may read it, and a failing section is left out rather than failing the page.

## Consequences
- Colours in feature code come from the theme (`color="brand"`, `c="dimmed"`) or tokens (`token('gain')`), never
  hex values. The contrast test and axe keep the palette honest.
- The fonts add about 70 kB of WOFF2 outside the JavaScript budget; the first page stays under its 330 kB budget.
- Badges change from upper case to sentence case; Playwright assertions follow.
- The home endpoint reads several modules; it calls only their public `api` interfaces and repeats their role
  rules where a module leaves them to its REST layer (reconciliation, settlement, audit).
