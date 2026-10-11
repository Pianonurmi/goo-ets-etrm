import { describe, expect, it } from 'vitest';
import { ageInDays, breakActions, breakSubject, facts, mismatches, type Break } from './breaks';

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
});
