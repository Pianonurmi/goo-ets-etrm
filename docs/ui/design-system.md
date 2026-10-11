# Design system

Provena's look and the parts every screen is built from (ADR 0034). The design board with the brand, palette, type,
components and the app shell is the reference; this page is how to use them in code.

## Brand

| | |
|---|---|
| Name | Provena (from *provenance*) |
| Mark | Three ledger lines shortening to a seal: `ProvenaMark` / `ProvenaLogo` in `app/brand/Logo.tsx`, `public/favicon.svg` |
| Type | Geist (text) and Geist Mono (codes, ids, keys), self-hosted via `@fontsource-variable` |
| Voice | Plain and calm: say what happened and what to do next; sentence case everywhere |

## Colour

Use theme colours (`color="brand"`, `c="dimmed"`, `color="red"`) or tokens (`token('gain')` → `var(--etrm-gain)`),
never hex values in feature code.

| Token | Light | Dark | Use |
|---|---|---|---|
| `canvas` | `#F8FAFC` | `#0B1220` | Page background behind cards |
| `surface` | `#FFFFFF` | `#111A2B` | Cards, header, sidebar |
| `line` | `#E2E8F0` | `#22304A` | Borders and dividers |
| `ink` / `muted` | `#0F172A` / `#475569` | `#E2E8F0` / `#94A3B8` | Text and secondary text |
| `accent` | `#0E6E7E` | `#127F91` (fill), `#3BB6C9` (text) | Primary actions, links, focus |
| `gain` / `loss` | `#15803D` / `#B91C1C` | `#4ADE80` / `#F87171` | Signed figures |
| `warning` / `info` | `#B45309` / `#1D4ED8` | `#FBBF24` / `#60A5FA` | Notices |
| `buy` / `sell` | `#1D4ED8` / `#C2410C` | `#60A5FA` / `#FB923C` | Trade sides (`<Side>`) |

All text colours meet WCAG AA (4.5:1) on their backgrounds in both schemes; `contrast.test.ts` and the axe checks in
`e2e/hardening.spec.ts` guard it.

### Status

`<StatusBadge status="PENDING_APPROVAL" />` renders every lifecycle with one map:

| Tone | Colour | Examples |
|---|---|---|
| neutral | grey | Draft, Cancelled, Closed, Superseded, Expired |
| waiting | amber | Pending approval, Proposed, Soft closed, Investigating |
| progress | blue | Open, Approved, Submitted, Issued, In transfer |
| done | green | Confirmed, Delivered, Settled, Paid, Official, Matched |
| problem | red | Rejected, Failed, Disputed, Mismatched, Overdue |

## Figures

- Every number uses tabular numerals (`.etrm-figure`, grids and tables do it automatically).
- Format with `shared/format`; show amounts with `<Money value currency signed />` (green or red when `signed`),
  quantities with `<Quantity value unit />`. Never compute money in the browser.

## Components

| Component | Where | Use |
|---|---|---|
| `PageHeader` | top of every screen | `breadcrumbs`, `title`, `badge` (a `StatusBadge`), `description`, `actions` (one primary) |
| `KpiCard` | overview pages | label, value with unit, trend line (`trendTone`), optional `spark` values, `to` link |
| `DetailLayout` + `FactGrid` | record pages | summary facts, then tabs; `aside` for timeline or tasks |
| `EmptyState` | empty lists | what would be here, why it is empty, the action that fills it |
| `TableSkeleton`, `CardSkeleton` | loading | instead of spinners, so the layout does not jump |
| `StatusBadge`, `Money`, `Quantity`, `Side` | anywhere | see above |
| `DataGrid` | lists | AG Grid in the theme; row height follows the density |

## Layout

- App shell: collapsible sidebar (`[`), top bar with legal entity, search (`Ctrl K` or `/`), environment badge,
  inbox bell, help and shortcuts (`?`), display settings (colour scheme `Shift L`, density `Shift D`), persona or
  user menu.
- Content sits on the canvas, up to 1440 px wide, in cards with 12 px radius and a 1 px line.
- Density: comfortable (default) or compact, per browser; compact shrinks rem sizes a step and grid rows to 32 px.
- Spacing follows Mantine's scale (`xs` 10, `sm` 12, `md` 16, `lg` 20, `xl` 32); radius `md` 8 px for controls,
  `lg` 12 px for cards.
