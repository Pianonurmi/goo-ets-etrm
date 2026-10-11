import { describe, expect, it } from 'vitest';
import {
  ageInDays,
  breakActions,
  breakSteps,
  breakSubject,
  canReconcile,
  facts,
  mismatches,
  type Break,
} from './breaks';

describe('reconciliation breaks', () => {
  it('offers the resolutions of each break type', () => {
    const missing = { type: 'MISSING_IN_REGISTRY', status: 'OPEN', lotId: 'l1' } as const;
    expect(breakActions(missing, 'HELD')).toMatchObject({
      take: true,
      withdrawLot: true,
      confirmInstruction: false,
      correctLot: true,
      accept: true,
    });
    expect(breakActions(missing, 'IN_TRANSFER')).toMatchObject({
      confirmInstruction: true,
      withdrawLot: false,
      correctLot: false,
    });
    expect(breakActions({ type: 'MISSING_INTERNALLY', status: 'INVESTIGATING', lotId: null })).toMatchObject({
      take: false,
      recordReceipt: true,
      correctLot: false,
    });
    expect(breakActions({ type: 'ATTRIBUTE_MISMATCH', status: 'OPEN', lotId: 'l1' }, 'HELD')).toMatchObject({
      replaceLot: true,
      correctLot: true,
    });
    expect(breakActions({ type: 'BALANCE_MISMATCH', status: 'OPEN', lotId: null }).correctHolding).toBe(true);
    const pending = breakActions({ type: 'MISSING_IN_REGISTRY', status: 'PENDING_ACCEPTANCE', lotId: 'l1' }, 'HELD');
    expect(pending).toMatchObject({ awaitingAcceptance: true, accept: false, withdrawLot: false, take: false });
    expect(breakActions({ type: 'MISSING_IN_REGISTRY', status: 'RESOLVED', lotId: 'l1' }, 'HELD').accept).toBe(false);
  });

  it('describes the subject and the age', () => {
    expect(
      breakSubject({ type: 'MISSING_IN_REGISTRY', issuanceRef: 'ISS-1', range: { start: 61, end: 100 } } as Break),
    ).toBe('ISS-1 61–100');
    expect(
      breakSubject({
        type: 'BALANCE_MISMATCH',
        productCode: 'EUA',
        registryQuantity: 100,
        internalQuantity: 90,
      } as Break),
    ).toBe('EUA: 100 / 90');
    expect(ageInDays({ firstSeenOn: '2026-10-01' }, '2026-10-11')).toBe(10);
    expect(ageInDays({ firstSeenOn: null, detectedAt: '2026-10-11T08:00:00Z' }, '2026-10-11')).toBe(0);
    expect(ageInDays({ firstSeenOn: null, detectedAt: null }, '2026-10-11')).toBeNull();
  });

  it('offers reconciling only a statement not yet reconciled', () => {
    expect(canReconcile({ status: 'IMPORTED' })).toBe(true);
    expect(canReconcile({ status: 'RECONCILED' })).toBe(false);
    expect(canReconcile({ status: 'BREAKS' })).toBe(false);
  });

  it('separates attribute mismatches from other details', () => {
    const details = {
      deviceGsrn: { registry: '643900000000000001', internal: '643900000000000002' },
      line: 3,
      status: 'HELD',
    };
    expect(mismatches(details)).toEqual([
      { field: 'deviceGsrn', registry: '643900000000000001', internal: '643900000000000002' },
    ]);
    expect(facts(details)).toEqual([
      ['line', '3'],
      ['status', 'HELD'],
    ]);
  });

  it('builds the lifecycle from opened to resolved', () => {
    const label = (key: string) => key;
    const open = {
      status: 'OPEN' as const,
      detectedAt: '2026-10-11T08:00:00Z',
      firstSeenOn: '2026-10-11',
      assignee: null,
      acceptanceRequestedBy: null,
      resolvedAt: null,
      resolvedBy: null,
      resolution: null,
    };
    expect(breakSteps(open, label).map((step) => [step.label, step.state])).toEqual([
      ['opened', 'current'],
      ['assigned', 'pending'],
      ['resolved', 'pending'],
    ]);
    const pending = { ...open, status: 'PENDING_ACCEPTANCE' as const, assignee: 'bo', acceptanceRequestedBy: 'bo' };
    expect(breakSteps(pending, label).map((step) => [step.label, step.state])).toEqual([
      ['opened', 'done'],
      ['assigned', 'done'],
      ['acceptanceRequested', 'current'],
      ['resolved', 'pending'],
    ]);
    const accepted = { ...pending, status: 'ACCEPTED' as const, resolvedBy: 'middle', resolution: 'Cut-off' };
    expect(breakSteps(accepted, label).at(-1)).toMatchObject({
      label: 'accepted',
      state: 'done',
      by: 'middle',
      detail: 'Cut-off',
    });
    expect(breakSteps({ ...open, status: 'AUTO_CLEARED' }, label).map((step) => step.label)).toEqual([
      'opened',
      'autoCleared',
    ]);
  });
});
