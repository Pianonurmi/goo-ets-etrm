import {
  affectedContracts,
  breakSteps,
  callSteps,
  chooseAccount,
  clearingTab,
  countByStatus,
  defaultRange,
  differenceKinds,
  differenceRows,
  figureKind,
  importSummary,
  isPositive,
  isZero,
  lastBusinessDay,
  newestFirst,
  postedFor,
  rangeIsValid,
  readRange,
  statusFilter,
  tradedContracts,
  BREAK_STATUSES,
  type Account,
  type ClearingBreak,
  type MarginCall,
  type Posted,
  type StatementImport,
} from './clearing';

const label = (key: string) => key;

const brk = (patch: Partial<ClearingBreak> = {}): ClearingBreak => ({
  id: 'b1',
  accountId: 'a1',
  legalEntityId: 'e1',
  businessDate: '2026-09-30',
  statementId: 's1',
  runId: 'r1',
  status: 'OPEN',
  openedAt: '2026-10-01T06:00:00Z',
  openedBy: 'bo',
  differences: [],
  ...patch,
});

const call = (patch: Partial<MarginCall> = {}): MarginCall => ({
  id: 'c1',
  accountId: 'a1',
  legalEntityId: 'e1',
  statementId: 's1',
  businessDate: '2026-09-30',
  amount: 1000,
  currency: 'EUR',
  status: 'OPEN',
  requestedAt: '2026-10-01T06:00:00Z',
  requestedBy: 'bo',
  ...patch,
});

describe('clearing tabs and filters', () => {
  it('falls back to the first tab', () => {
    expect(clearingTab('margin-calls')).toBe('margin-calls');
    expect(clearingTab('margin')).toBe('margin');
    expect(clearingTab('nope')).toBe('accounts');
    expect(clearingTab(null)).toBe('accounts');
  });

  it('reads a status filter from the URL', () => {
    expect(statusFilter(null, BREAK_STATUSES, 'OPEN')).toBe('OPEN');
    expect(statusFilter('ACCEPTED', BREAK_STATUSES, 'OPEN')).toBe('ACCEPTED');
    expect(statusFilter('ALL', BREAK_STATUSES, 'OPEN')).toBeNull();
    expect(statusFilter('bogus', BREAK_STATUSES, 'OPEN')).toBeNull();
  });

  it('chooses the account of the URL, else the first', () => {
    const accounts = [{ id: 'a1' }, { id: 'a2' }] as Account[];
    expect(chooseAccount(accounts, 'a2')?.id).toBe('a2');
    expect(chooseAccount(accounts, 'gone')?.id).toBe('a1');
    expect(chooseAccount(accounts, null)?.id).toBe('a1');
    expect(chooseAccount([], 'a1')).toBeNull();
  });

  it('finds the margin posted for an account', () => {
    const posted = [{ accountId: 'a1' }, { accountId: 'a2' }] as Posted[];
    expect(postedFor(posted, 'a2')?.accountId).toBe('a2');
    expect(postedFor(posted, undefined)).toBeUndefined();
    expect(postedFor(posted, 'a3')).toBeUndefined();
  });

  it('counts rows by status', () => {
    expect(countByStatus([{ status: 'OPEN' }, { status: 'PAID' }, { status: 'OPEN' }])).toEqual({ OPEN: 2, PAID: 1 });
    expect(countByStatus([])).toEqual({});
  });
});

describe('date ranges', () => {
  it('defaults to the last month', () => {
    expect(defaultRange('2026-10-13')).toEqual({ from: '2026-09-12', to: '2026-10-13' });
  });

  it('moves a weekend back to Friday', () => {
    expect(lastBusinessDay('2026-10-13')).toBe('2026-10-13');
    expect(lastBusinessDay('2026-10-17')).toBe('2026-10-16');
    expect(lastBusinessDay('2026-10-18')).toBe('2026-10-16');
  });

  it('keeps valid dates from the URL and replaces bad ones', () => {
    expect(readRange('2026-01-01', '2026-02-01', '2026-10-13')).toEqual({ from: '2026-01-01', to: '2026-02-01' });
    expect(readRange('yesterday', null, '2026-10-13')).toEqual({ from: '2026-09-12', to: '2026-10-13' });
  });

  it('refuses a range that ends before it starts', () => {
    expect(rangeIsValid({ from: '2026-02-01', to: '2026-02-01' })).toBe(true);
    expect(rangeIsValid({ from: '2026-02-02', to: '2026-02-01' })).toBe(false);
  });

  it('sorts newest business date first, newest version first within a day', () => {
    const rows = [
      { businessDate: '2026-09-29', version: 1 },
      { businessDate: '2026-09-30', version: 1 },
      { businessDate: '2026-09-30', version: 2 },
    ];
    expect(newestFirst(rows)).toEqual([
      { businessDate: '2026-09-30', version: 2 },
      { businessDate: '2026-09-30', version: 1 },
      { businessDate: '2026-09-29', version: 1 },
    ]);
    expect(rows[0]?.businessDate).toBe('2026-09-29');
  });
});

describe('margin runs', () => {
  it('compares decimals that arrive as numbers or strings', () => {
    expect(isZero(0)).toBe(true);
    expect(isZero('0.00')).toBe(true);
    expect(isZero('-3')).toBe(false);
    expect(isPositive('12.50')).toBe(true);
    expect(isPositive(-1)).toBe(false);
    expect(isPositive(0)).toBe(false);
  });

  it('counts the contracts traded that day', () => {
    const lines = [{ tradedQuantity: 0 }, { tradedQuantity: 5 }, { tradedQuantity: -2 }] as never;
    expect(tradedContracts({ lines })).toBe(2);
  });
});

describe('break differences', () => {
  const differences: ClearingBreak['differences'] = [
    { kind: 'VARIATION_MARGIN', contractCode: 'EUA-DEC26', ours: 100, theirs: 90 },
    { kind: 'POSITION', contractCode: 'EUA-DEC27', ours: 10, theirs: 12 },
    { kind: 'SETTLEMENT_PRICE', contractCode: 'EUA-DEC26', ours: 70.5, theirs: 70.4 },
    { kind: 'NO_RUN' },
  ];

  it('lists the kinds in explanation order', () => {
    expect(differenceKinds(brk({ differences }))).toEqual([
      'NO_RUN',
      'POSITION',
      'VARIATION_MARGIN',
      'SETTLEMENT_PRICE',
    ]);
    expect(differenceKinds(brk())).toEqual([]);
  });

  it('orders rows by contract, then kind, with the missing run first', () => {
    const rows = differenceRows(brk({ differences }));
    expect(rows.map((row) => [row.contractCode, row.kind])).toEqual([
      [null, 'NO_RUN'],
      ['EUA-DEC26', 'VARIATION_MARGIN'],
      ['EUA-DEC26', 'SETTLEMENT_PRICE'],
      ['EUA-DEC27', 'POSITION'],
    ]);
    expect(new Set(rows.map((row) => row.key)).size).toBe(rows.length);
    expect(rows[0]).toMatchObject({ ours: null, theirs: null });
  });

  it('names the contracts once', () => {
    expect(affectedContracts(brk({ differences }))).toEqual(['EUA-DEC26', 'EUA-DEC27']);
  });

  it('formats positions, margin and prices differently', () => {
    expect(figureKind('POSITION')).toBe('quantity');
    expect(figureKind('VARIATION_MARGIN')).toBe('money');
    expect(figureKind('SETTLEMENT_PRICE')).toBe('price');
    expect(figureKind('NO_RUN')).toBe('none');
  });
});

describe('lifecycles', () => {
  it('shows an open break waiting for its close', () => {
    const steps = breakSteps(brk(), label);
    expect(steps.map((step) => [step.label, step.state])).toEqual([
      ['opened', 'current'],
      ['closed', 'pending'],
    ]);
  });

  it('ends a matched or accepted break with who closed it and why', () => {
    const matched = breakSteps(brk({ status: 'MATCHED', closedAt: '2026-10-02T06:00:00Z', closedBy: 'system' }), label);
    expect(matched.map((step) => [step.label, step.state])).toEqual([
      ['opened', 'done'],
      ['matched', 'current'],
    ]);
    const accepted = breakSteps(
      brk({ status: 'ACCEPTED', closedBy: 'mo', resolution: 'Member figures agreed' }),
      label,
    );
    expect(accepted[1]).toMatchObject({ label: 'accepted', by: 'mo', detail: 'Member figures agreed' });
  });

  it('follows a margin call to payment, dispute or cancellation', () => {
    expect(callSteps(call(), label).map((step) => [step.label, step.state])).toEqual([
      ['requested', 'current'],
      ['decision', 'pending'],
    ]);
    expect(callSteps(call({ status: 'PAID', decidedBy: 'ba' }), label)[1]).toMatchObject({
      label: 'paid',
      state: 'current',
      by: 'ba',
    });
    expect(callSteps(call({ status: 'DISPUTED', reason: 'Wrong amount' }), label)[1]).toMatchObject({
      label: 'disputed',
      state: 'failed',
      detail: 'Wrong amount',
    });
    expect(callSteps(call({ status: 'CANCELLED' }), label)[1]?.label).toBe('cancelled');
  });
});

describe('statement import', () => {
  it('summarises new versions, unchanged days and open breaks', () => {
    const result = {
      imported: [{ businessDate: '2026-09-30' }, { businessDate: '2026-09-29' }],
      unchanged: 3,
      breaks: [{ status: 'OPEN' }, { status: 'MATCHED' }],
    } as StatementImport;
    expect(importSummary(result)).toEqual({
      imported: 2,
      unchanged: 3,
      breaks: 1,
      firstDate: '2026-09-29',
      lastDate: '2026-09-30',
    });
    expect(importSummary({ imported: [], unchanged: 1, breaks: [] })).toMatchObject({
      imported: 0,
      firstDate: null,
      lastDate: null,
    });
  });
});
