import { dueWithin, tradeSteps } from './lifecycle';

const label = (key: string) => key;
const trade = {
  status: 'PENDING_APPROVAL' as const,
  createdBy: 'trader',
  approvedAt: null,
  approvedBy: null,
  confirmedAt: null,
  confirmationRef: null,
};
const v = (version: number, status: string, recordedBy = 'someone') => ({
  version,
  status: status as never,
  recordedAt: `2026-10-0${version}T09:00:00Z`,
  recordedBy,
});

describe('tradeSteps', () => {
  it('shows a pending trade captured and waiting for approval', () => {
    const steps = tradeSteps(trade, [v(1, 'PENDING_APPROVAL', 'trader')], label);
    expect(steps.map((step) => [step.label, step.state])).toEqual([
      ['captured', 'current'],
      ['approved', 'pending'],
      ['confirmed', 'pending'],
      ['delivered', 'pending'],
      ['settled', 'pending'],
    ]);
    expect(steps[0]).toMatchObject({ at: '2026-10-01T09:00:00Z', by: 'trader' });
  });

  it('takes approval and confirmation from the trade, delivery and settlement from its versions', () => {
    const steps = tradeSteps(
      {
        ...trade,
        status: 'SETTLED',
        approvedAt: '2026-10-02T10:00:00Z',
        approvedBy: 'middle',
        confirmedAt: '2026-10-03T10:00:00Z',
        confirmationRef: 'CONF-1',
      },
      [v(1, 'PENDING_APPROVAL'), v(2, 'APPROVED'), v(3, 'CONFIRMED'), v(4, 'DELIVERED', 'backoffice'), v(5, 'SETTLED')],
      label,
    );
    expect(steps.every((step) => step.state === 'done')).toBe(true);
    expect(steps[1]).toMatchObject({ by: 'middle' });
    expect(steps[2]).toMatchObject({ detail: 'CONF-1' });
    expect(steps[3]).toMatchObject({ at: '2026-10-04T09:00:00Z', by: 'backoffice' });
  });

  it('marks a partly delivered trade as delivering', () => {
    const steps = tradeSteps({ ...trade, status: 'PARTIALLY_DELIVERED' }, [], label);
    expect(steps[2]?.state).toBe('done');
    expect(steps[3]).toMatchObject({ state: 'current', detail: 'partlyDelivered' });
  });

  it('ends a cancelled trade after the last step it reached', () => {
    const steps = tradeSteps(
      { ...trade, status: 'CANCELLED', approvedBy: 'middle' },
      [v(1, 'PENDING_APPROVAL'), v(2, 'APPROVED'), v(3, 'CANCELLED', 'middle')],
      label,
    );
    expect(steps.map((step) => [step.label, step.state])).toEqual([
      ['captured', 'done'],
      ['approved', 'done'],
      ['cancelled', 'failed'],
    ]);
    expect(steps[2]).toMatchObject({ by: 'middle' });
  });

  it('ends an option at expiry instead of delivery and settlement', () => {
    const option = { ...trade, instrumentType: 'OPTION' as const };
    const confirmed = tradeSteps(
      {
        ...option,
        status: 'CONFIRMED',
        approvedBy: 'middle',
        approvedAt: '2026-10-01T10:00:00Z',
        confirmedAt: '2026-10-01T11:00:00Z',
        confirmationRef: 'EEX-1',
      },
      [],
      (key) => key,
    );
    expect(confirmed.map((step) => step.label)).toEqual(['captured', 'approved', 'confirmed', 'expired']);
    expect(confirmed.at(-1)?.state).toBe('pending');
    const closed = tradeSteps(
      {
        ...option,
        status: 'CLOSED',
        approvedBy: 'middle',
        approvedAt: '2026-10-01T10:00:00Z',
        confirmedAt: '2026-10-01T11:00:00Z',
        confirmationRef: 'EEX-1',
      },
      [{ version: 5, status: 'CLOSED', recordedAt: '2026-12-04T18:00:00Z', recordedBy: 'system' }],
      (key) => key,
    );
    expect(closed.at(-1)).toMatchObject({ label: 'expired', state: 'done', by: 'system' });
  });
});

describe('dueWithin', () => {
  it('counts the deliveries due within the window, overdue ones included', () => {
    const obligations = [
      { deliveryDate: '2026-09-30' },
      { deliveryDate: '2026-10-31' },
      { deliveryDate: '2026-12-15' },
    ];
    expect(dueWithin(obligations, '2026-10-11', 30)).toBe(2);
  });
});
