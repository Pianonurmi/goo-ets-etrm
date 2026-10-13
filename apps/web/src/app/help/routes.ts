/** The help pages, in manual order. Each is `docs/user-guide/<id>.md`. */
export const HELP_PAGES = [
  'getting-started',
  'demo',
  'trading',
  'confirmations',
  'inventory',
  'allocation',
  'positions',
  'registry',
  'reconciliation',
  'settlement',
  'clearing',
  'market-data',
  'valuation',
  'risk',
  'ets',
  'ledger',
  'reports',
  'administration',
  'glossary',
] as const;

export type HelpPageId = (typeof HELP_PAGES)[number];

export interface HelpTarget {
  page: HelpPageId;
  anchor?: string;
}

/** Screen path prefixes and the help that explains them; the most specific prefix wins. */
const ROUTES: [prefix: string, target: HelpTarget][] = [
  ['/inbox', { page: 'getting-started', anchor: 'the-inbox' }],
  ['/trades/new', { page: 'trading', anchor: 'capture-a-trade' }],
  ['/trades', { page: 'trading' }],
  ['/confirmations', { page: 'confirmations' }],
  ['/inventory', { page: 'inventory' }],
  ['/allocation', { page: 'allocation' }],
  ['/positions', { page: 'positions' }],
  ['/registry', { page: 'registry' }],
  ['/reconciliation', { page: 'reconciliation' }],
  ['/settlement', { page: 'settlement' }],
  ['/clearing', { page: 'clearing' }],
  ['/market-data/curves', { page: 'market-data', anchor: 'curves' }],
  ['/market-data', { page: 'market-data' }],
  ['/valuation', { page: 'valuation' }],
  ['/risk', { page: 'risk' }],
  ['/ets', { page: 'ets' }],
  ['/ledger/hedges', { page: 'ledger', anchor: 'cash-flow-hedges' }],
  ['/ledger', { page: 'ledger' }],
  ['/month-end', { page: 'ledger', anchor: 'month-end' }],
  ['/reports', { page: 'reports' }],
  ['/admin/demo', { page: 'demo', anchor: 'resetting-the-demo' }],
  ['/admin', { page: 'administration' }],
  ['/audit', { page: 'administration', anchor: 'audit-trail' }],
  ['/guide', { page: 'demo' }],
  ['/help', { page: 'getting-started' }],
];

/** The help for the screen at [pathname]; the home page and unknown screens get the getting-started page. */
export function helpFor(pathname: string): HelpTarget {
  const match = ROUTES.filter(([prefix]) => pathname === prefix || pathname.startsWith(`${prefix}/`)).sort(
    (a, b) => b[0].length - a[0].length,
  )[0];
  return match ? match[1] : { page: 'getting-started' };
}

export function isHelpPage(id: string | undefined): id is HelpPageId {
  return (HELP_PAGES as readonly string[]).includes(id ?? '');
}

/**
 * Where a link inside a help page goes: another page (`trading.md#capture-a-trade` → `/help/trading#capture-a-trade`),
 * an anchor on this page, or an external address (opened in a new tab).
 */
export function resolveHelpLink(href: string, currentPage: HelpPageId): { kind: 'internal' | 'external'; to: string } {
  if (/^https?:\/\//.test(href) || href.startsWith('mailto:')) return { kind: 'external', to: href };
  if (href.startsWith('#')) return { kind: 'internal', to: `/help/${currentPage}${href}` };
  const match = /^(?:\.\/)?([a-z0-9-]+)\.md(#[a-z0-9-]+)?$/.exec(href);
  if (match) return { kind: 'internal', to: `/help/${match[1]}${match[2] ?? ''}` };
  return { kind: 'internal', to: href };
}

/** Images live in `docs/images` and are served at `/help/images/` (copied there by the build). */
export function helpImage(src: string): string {
  const name = src.split('/').pop() ?? src;
  return `/help/images/${name}`;
}
