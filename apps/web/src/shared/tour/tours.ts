/**
 * The guided tours of the demo (increment 7 M11). A step points at an element by CSS selector, preferably a
 * `data-testid` (stable: the end-to-end tests rely on them too); when it cannot be found, the step is shown in the
 * middle of the screen. A step may name the persona it is meant for. Texts live in `locales/tour.en.json`.
 */
export interface TourStep {
  id: string;
  /** Where the step happens; the tour navigates there first. */
  path?: string;
  /** The element to point at. */
  target?: string;
  /** The persona the step is meant for; the tour offers to switch. */
  persona?: string;
}

export interface TourDefinition {
  id: string;
  minutes: number;
  personas: string[];
  steps: TourStep[];
}

const id = (testId: string) => `[data-testid="${testId}"]`;
const nav = (path: string) => `nav a[href="${path}"]`;
const TITLE = '.etrm-page-title';

export const TOURS: TourDefinition[] = [
  {
    id: 'orientation',
    minutes: 2,
    personas: ['trader', 'middle'],
    steps: [
      { id: 'welcome', path: '/' },
      { id: 'kpis', path: '/', target: id('home-kpis') },
      { id: 'work', path: '/', target: id('my-tasks'), persona: 'middle' },
      { id: 'entity', target: id('entity-select') },
      { id: 'search', target: id('palette-button') },
      { id: 'inbox', target: nav('/inbox') },
      { id: 'persona', target: id('persona-menu') },
      { id: 'help', target: id('help-button') },
    ],
  },
  {
    id: 'trade',
    minutes: 4,
    personas: ['trader', 'middle'],
    steps: [
      { id: 'form', path: '/trades/new', target: id('field-instrument'), persona: 'trader' },
      { id: 'counterparty', path: '/trades/new', target: id('field-counterparty'), persona: 'trader' },
      { id: 'pricing', path: '/trades/new', target: id('leg-0-price-type'), persona: 'trader' },
      { id: 'criteria', path: '/trades/new', target: id('criteria-summary'), persona: 'trader' },
      { id: 'capture', path: '/trades/new', target: id('submit-trade'), persona: 'trader' },
      { id: 'approve', path: '/inbox', target: id('inbox-count'), persona: 'middle' },
      { id: 'blotter', path: '/trades', target: id('trade-search') },
    ],
  },
  {
    id: 'confirmations',
    minutes: 2,
    personas: ['backoffice', 'middle2'],
    steps: [
      { id: 'list', path: '/confirmations', target: TITLE, persona: 'backoffice' },
      { id: 'ours', path: '/trades', target: id('trade-search'), persona: 'backoffice' },
      { id: 'break', path: '/inbox', target: id('inbox-count'), persona: 'middle2' },
    ],
  },
  {
    id: 'inventory',
    minutes: 3,
    personas: ['backoffice'],
    steps: [
      { id: 'lots', path: '/inventory', target: id('lot-totals'), persona: 'backoffice' },
      { id: 'ladder', path: '/inventory', target: id('expiry-ladder'), persona: 'backoffice' },
      { id: 'criteria', path: '/inventory?tab=search', target: id('criteria-summary'), persona: 'backoffice' },
      { id: 'search', path: '/inventory?tab=search', target: id('run-search'), persona: 'backoffice' },
      { id: 'allowances', path: '/inventory?tab=allowances', target: TITLE, persona: 'backoffice' },
    ],
  },
  {
    id: 'allocation',
    minutes: 3,
    personas: ['trader', 'middle'],
    steps: [
      { id: 'workbench', path: '/allocation', target: TITLE, persona: 'trader' },
      { id: 'solver', path: '/allocation', target: id('solver'), persona: 'trader' },
      { id: 'whatIf', path: '/allocation', target: id('what-if'), persona: 'trader' },
      { id: 'propose', path: '/allocation', target: id('propose'), persona: 'trader' },
      { id: 'approve', path: '/inbox', target: id('inbox-count'), persona: 'middle' },
      { id: 'registry', path: '/registry', target: TITLE, persona: 'backoffice' },
    ],
  },
  {
    id: 'valuation',
    minutes: 3,
    personas: ['risk', 'risk2'],
    steps: [
      { id: 'curves', path: '/market-data/curves', target: TITLE, persona: 'risk' },
      { id: 'snapshot', path: '/market-data/curves', target: id('build-snapshot'), persona: 'risk' },
      { id: 'runs', path: '/valuation', target: TITLE, persona: 'risk' },
      { id: 'explain', path: '/valuation?tab=explain', target: TITLE, persona: 'risk' },
      { id: 'signOff', path: '/inbox', target: id('inbox-count'), persona: 'risk2' },
    ],
  },
  {
    id: 'risk',
    minutes: 2,
    personas: ['risk'],
    steps: [
      { id: 'limits', path: '/risk', target: TITLE, persona: 'risk' },
      { id: 'measure', path: '/risk', target: id('measure'), persona: 'risk' },
      { id: 'request', path: '/risk', target: id('request-limit'), persona: 'risk' },
      { id: 'breaches', path: '/risk?tab=breaches', target: TITLE, persona: 'risk' },
    ],
  },
  {
    id: 'ets',
    minutes: 2,
    personas: ['middle'],
    steps: [
      { id: 'position', path: '/ets', target: TITLE, persona: 'middle' },
      { id: 'deadlines', path: '/ets', target: id('deadline-calendar'), persona: 'middle' },
      { id: 'installations', path: '/ets', target: id('installations'), persona: 'middle' },
      { id: 'emissions', path: '/ets', target: `${id('installations')} a`, persona: 'middle' },
    ],
  },
  {
    id: 'settlement',
    minutes: 4,
    personas: ['accountant', 'controller'],
    steps: [
      { id: 'items', path: '/settlement?view=open', target: TITLE, persona: 'accountant' },
      { id: 'run', path: '/settlement?view=open', target: id('run-invoices'), persona: 'accountant' },
      { id: 'issue', path: '/inbox', target: id('inbox-count'), persona: 'controller' },
      { id: 'payments', path: '/settlement?tab=payments', target: id('record-payment'), persona: 'accountant' },
      { id: 'netting', path: '/settlement?tab=netting', target: TITLE, persona: 'accountant' },
    ],
  },
  {
    id: 'monthEnd',
    minutes: 3,
    personas: ['accountant'],
    steps: [
      { id: 'period', path: '/month-end', target: id('period-picker'), persona: 'accountant' },
      { id: 'status', path: '/month-end', target: id('period-status'), persona: 'accountant' },
      { id: 'periodEnd', path: '/month-end', target: id('period-end-run'), persona: 'accountant' },
      { id: 'checklist', path: '/month-end', target: id('hard-close-checklist'), persona: 'accountant' },
      { id: 'ledger', path: '/ledger', target: TITLE, persona: 'accountant' },
    ],
  },
  {
    id: 'admin',
    minutes: 2,
    personas: ['middle', 'auditor', 'admin'],
    steps: [
      { id: 'reference', path: '/admin/reference', target: TITLE, persona: 'middle' },
      { id: 'changes', path: '/admin/change-requests', target: TITLE, persona: 'middle' },
      { id: 'audit', path: '/audit', target: TITLE, persona: 'auditor' },
      { id: 'demo', path: '/admin/demo', target: TITLE, persona: 'admin' },
    ],
  },
];

export function tourById(tourId: string): TourDefinition | undefined {
  return TOURS.find((tour) => tour.id === tourId);
}

/** The tour that explains the screen at [pathname]: the first whose first step happens there. */
export function tourForPath(pathname: string): TourDefinition | undefined {
  return TOURS.find((tour) => tour.steps.some((step) => step.path?.split('?')[0] === pathname));
}

/** Whether the browser must navigate before showing [step]: its path differs from where we are. */
export function needsNavigation(step: TourStep, pathname: string, search: string): boolean {
  if (!step.path) return false;
  const [path, query] = step.path.split('?');
  if (path !== pathname) return true;
  if (!query) return false;
  const wanted = new URLSearchParams(query);
  const current = new URLSearchParams(search);
  return [...wanted.entries()].some(([key, value]) => current.get(key) !== value);
}

export interface Rect {
  top: number;
  left: number;
  width: number;
  height: number;
}

/**
 * Where the step's card goes: below the target when it fits, else above, else centred; always inside the viewport
 * with a margin.
 */
export function placeCard(
  target: Rect | null,
  card: { width: number; height: number },
  viewport: { width: number; height: number },
  gap = 12,
  margin = 16,
): { top: number; left: number } {
  const clampLeft = (left: number) => Math.min(Math.max(left, margin), viewport.width - card.width - margin);
  if (!target) {
    return {
      top: Math.max(margin, (viewport.height - card.height) / 2),
      left: clampLeft((viewport.width - card.width) / 2),
    };
  }
  const left = clampLeft(target.left + target.width / 2 - card.width / 2);
  // Whatever the target's position (it may be off screen while the page settles), the card stays on screen.
  const clampTop = (top: number) => Math.max(margin, Math.min(top, viewport.height - card.height - margin));
  const below = target.top + target.height + gap;
  if (below >= margin && below + card.height + margin <= viewport.height) return { top: below, left };
  const above = target.top - gap - card.height;
  if (above >= margin && above + card.height + margin <= viewport.height) return { top: above, left };
  return { top: clampTop(viewport.height - card.height - margin), left };
}
