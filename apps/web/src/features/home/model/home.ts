/** Pure helpers of the home dashboard (display only; figures are computed by the server). */

export function greeting(hour: number): string {
  if (hour < 5 || hour >= 18) return 'home.greetingEvening';
  if (hour < 12) return 'home.greetingMorning';
  return 'home.greetingAfternoon';
}

/** The sign of a decimal the server sent, read from its text. */
export function isNegative(value: string | number): boolean {
  return String(value).trim().startsWith('-');
}

/** The headline figures the home page can show; which ones lead depends on the role. */
export type KpiKey = 'tasks' | 'trades' | 'coverage' | 'live' | 'mtm' | 'eua' | 'breaks' | 'overdue' | 'risk';

const DEFAULT_ORDER: KpiKey[] = ['tasks', 'coverage', 'mtm', 'eua', 'breaks', 'overdue', 'risk', 'trades', 'live'];

/** What each role looks at first: the front office its book, operations its exceptions, finance its money. */
const ROLE_ORDER: Record<string, KpiKey[]> = {
  TRADER: ['mtm', 'coverage', 'eua', 'trades', 'risk', 'live', 'tasks', 'breaks', 'overdue'],
  MIDDLE_OFFICE: ['tasks', 'trades', 'risk', 'coverage', 'mtm', 'breaks', 'eua', 'overdue', 'live'],
  BACK_OFFICE: ['tasks', 'breaks', 'overdue', 'live', 'coverage', 'eua', 'trades', 'mtm', 'risk'],
  REGISTRY_OPERATOR: ['tasks', 'breaks', 'live', 'coverage', 'eua', 'overdue', 'trades', 'mtm', 'risk'],
  ACCOUNTANT: ['tasks', 'overdue', 'mtm', 'breaks', 'eua', 'coverage', 'live', 'trades', 'risk'],
  RISK: ['risk', 'mtm', 'eua', 'coverage', 'tasks', 'trades', 'breaks', 'overdue', 'live'],
  AUDITOR: ['breaks', 'overdue', 'risk', 'mtm', 'eua', 'coverage', 'trades', 'live', 'tasks'],
};

/**
 * Orders the available figures for the user's roles: each figure takes its best rank among the roles, ties keep the
 * default order. The first few lead the page; the rest go to the watchlist.
 */
export function orderKpis(available: readonly KpiKey[], roles: readonly string[]): KpiKey[] {
  const orders = roles.map((role) => ROLE_ORDER[role]).filter((order): order is KpiKey[] => !!order);
  const rank = (key: KpiKey) =>
    orders.length === 0 ? DEFAULT_ORDER.indexOf(key) : Math.min(...orders.map((order) => order.indexOf(key)));
  return [...available].sort((a, b) => rank(a) - rank(b) || DEFAULT_ORDER.indexOf(a) - DEFAULT_ORDER.indexOf(b));
}

/** `TradeApproved` → `Trade approved`: an audit event type as words. */
export function eventLabel(eventType: string): string {
  const words = eventType
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .toLowerCase()
    .trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
