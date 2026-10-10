/**
 * Where a task's subject lives in the UI. Screens arrive milestone by milestone; a subject without a screen yet
 * shows its reference only.
 */
const SUBJECT_ROUTES: Record<string, (ref: string) => string> = {
  TRADE: (ref) => `/trades/${ref}`,
  ALLOCATION_PLAN: (ref) => `/allocation/plans/${ref}`,
  CURVE_SNAPSHOT: (ref) => `/market-data/snapshots/${ref}`,
  VALUATION_RUN: (ref) => `/valuation/runs/${ref}`,
  RISK_LIMIT: (ref) => `/risk/limits/${ref}`,
  LIMIT_BREACH: () => '/risk?tab=breaches',
  CREDIT_SUPPORT: () => '/risk?tab=credit-support',
  EMISSION_RECORD: () => '/ets',
  COMPLIANCE_DEADLINE: () => '/ets',
  ACCOUNTING_POLICY_CHANGE: () => '/month-end/policies',
  POSTING_RULE: () => '/ledger/rules',
  CHANGE_REQUEST: () => '/admin/change-requests',
};

/** Subject types whose screens exist; extended as screens are added. */
const AVAILABLE = new Set<string>([]);

export function subjectLink(subjectType: string, subjectRef: string): string | null {
  const route = SUBJECT_ROUTES[subjectType];
  return route && AVAILABLE.has(subjectType) ? route(subjectRef) : null;
}

/** Marks subject types whose screens are routed (called by the router module). */
export function registerSubjectScreens(...types: string[]): void {
  types.forEach((type) => AVAILABLE.add(type));
}
