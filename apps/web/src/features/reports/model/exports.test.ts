import { describe, expect, it } from 'vitest';
import { canDownloadExport, errorReportableEvents, exportFileName, type EmirEvent } from './exports';

function event(id: string, tradeId: string, action: EmirEvent['action'], recordedAt: string): EmirEvent {
  return {
    id,
    tradeId,
    action,
    recordedAt,
    eventDate: recordedAt.slice(0, 10),
    fields: {},
    legalEntityId: 'e-1',
    recordedBy: 'system',
    sourceRef: id,
    tradeNumber: `T-${tradeId}`,
    tradeVersion: 1,
  };
}

describe('report exports and EMIR errors', () => {
  it('lets whoever may run the report download its export again', () => {
    const runnable = [{ code: 'GL_TRIAL_BALANCE' }];
    expect(canDownloadExport({ reportCode: 'GL_TRIAL_BALANCE' }, runnable)).toBe(true);
    expect(canDownloadExport({ reportCode: 'EMIR_ACTIVITY' }, runnable)).toBe(false);
  });

  it('offers no download once the content expired', () => {
    const runnable = [{ code: 'GL_TRIAL_BALANCE' }];
    expect(
      canDownloadExport({ reportCode: 'GL_TRIAL_BALANCE', contentExpiredAt: '2027-11-20T00:00:00Z' }, runnable),
    ).toBe(false);
    expect(canDownloadExport({ reportCode: 'GL_TRIAL_BALANCE', contentExpiredAt: null }, runnable)).toBe(true);
  });

  it('names the file by report, number and format', () => {
    expect(exportFileName({ reportCode: 'EMIR_ACTIVITY', seq: 3, format: 'CSV' })).toBe('emir_activity-3.csv');
    expect(exportFileName({ reportCode: 'EMIR_ACTIVITY', seq: 4, format: 'JSON' })).toBe('emir_activity-4.json');
  });

  it('offers an error report on the latest lifecycle event of trades not yet in error', () => {
    const events = [
      event('a1', 'a', 'NEWT', '2026-09-01T10:00:00Z'),
      event('a2', 'a', 'MODI', '2026-09-05T10:00:00Z'),
      event('a3', 'a', 'VALU', '2026-09-30T18:00:00Z'),
      event('b1', 'b', 'NEWT', '2026-09-02T10:00:00Z'),
      event('b2', 'b', 'EROR', '2026-09-03T10:00:00Z'),
      event('c1', 'c', 'NEWT', '2026-09-04T10:00:00Z'),
    ];
    expect([...errorReportableEvents(events)].sort()).toEqual(['a2', 'c1']);
  });
});
