import { describe, expect, it } from 'vitest';
import {
  asQueueView,
  instructionActions,
  lifecycle,
  queueStatuses,
  routeSummary,
  subject,
  type Instruction,
  type Route,
} from './instructions';

const base = {
  id: 'i1',
  status: 'PENDING_APPROVAL',
  channel: 'SIMULATOR',
  createdAt: '2026-10-11T08:00:00Z',
  submittedBy: 'backoffice',
  quantity: 50,
  assetKind: 'GOO_LOT',
  range: { start: 1, end: 50 },
} as unknown as Instruction;

describe('registry instructions', () => {
  it('maps queue views to statuses and defaults to open work', () => {
    expect(queueStatuses('attention')).toEqual(['UNKNOWN', 'FAILED']);
    expect(queueStatuses('all')).toBeUndefined();
    expect(asQueueView('done')).toBe('done');
    expect(asQueueView(null)).toBe('open');
  });

  it('offers the actions each state allows', () => {
    expect(instructionActions({ status: 'PENDING_APPROVAL', channel: 'SIMULATOR' })).toEqual({
      confirm: false,
      fail: false,
      cancel: true,
      retry: false,
      awaitingApproval: true,
    });
    expect(instructionActions({ status: 'SENT', channel: 'API' })).toMatchObject({
      confirm: true,
      fail: true,
      cancel: false,
    });
    expect(instructionActions({ status: 'UNKNOWN', channel: 'API' })).toMatchObject({ confirm: true, retry: true });
    expect(instructionActions({ status: 'PENDING', channel: 'MANUAL' })).toMatchObject({ confirm: true, cancel: true });
    expect(instructionActions({ status: 'PENDING', channel: 'API' })).toMatchObject({ confirm: false, cancel: true });
    expect(instructionActions({ status: 'DISPATCHING', channel: 'API' })).toMatchObject({
      confirm: false,
      cancel: false,
      retry: false,
    });
    expect(instructionActions({ status: 'FAILED', channel: 'API' })).toMatchObject({ retry: true, cancel: true });
    expect(instructionActions({ status: 'CONFIRMED', channel: 'API' })).toMatchObject({
      confirm: false,
      cancel: false,
      retry: false,
    });
  });

  it('builds the lifecycle from the timestamps', () => {
    expect(lifecycle(base).map((step) => [step.key, step.done])).toEqual([
      ['submitted', true],
      ['approved', false],
      ['confirmed', false],
    ]);
    const confirmed = {
      ...base,
      status: 'CONFIRMED',
      approvedBy: 'middle',
      approvedAt: '2026-10-11T09:00:00Z',
      sentAt: '2026-10-11T09:00:01Z',
      confirmedBy: 'system',
      confirmedAt: '2026-10-11T09:00:02Z',
    } as Instruction;
    expect(lifecycle(confirmed).map((step) => step.key)).toEqual(['submitted', 'approved', 'sent', 'confirmed']);
    expect(lifecycle({ ...base, status: 'FAILED' }).at(-1)?.key).toBe('failed');
  });

  it('describes what moves and the route', () => {
    expect(subject(base)).toBe('50 MWh (1–50)');
    expect(subject({ ...base, assetKind: 'ALLOWANCE', productCode: 'EUA', range: null })).toBe('50 t EUA');
    const route = { fromRegistryCode: 'SIMULATOR', toRegistryCode: 'SIMULATOR', feasible: true } as Route;
    expect(routeSummary(route)).toEqual({ feasible: true, same: true });
    expect(routeSummary(undefined)).toBeNull();
  });
});
