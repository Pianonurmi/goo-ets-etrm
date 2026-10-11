import { eventLabel, historySteps } from './history';

describe('lot history', () => {
  it('makes event names readable', () => {
    expect(eventLabel('LotReceived')).toBe('Received');
    expect(eventLabel('LotTransferInitiated')).toBe('Transfer initiated');
    expect(eventLabel('Lottery')).toBe('Lottery');
  });

  it('lists events oldest first, the latest as the current state', () => {
    const steps = historySteps([
      { seq: 12, eventType: 'LotCorrected', actor: 'backoffice', recordedAt: '2026-10-11T10:00:00Z' },
      { seq: 11, eventType: 'LotReceived', actor: 'backoffice', recordedAt: '2026-10-11T09:00:00Z' },
    ]);
    expect(steps.map((step) => [step.label, step.state, step.detail])).toEqual([
      ['Received', 'done', 'LotReceived'],
      ['Corrected', 'current', 'LotCorrected'],
    ]);
    expect(historySteps([])).toEqual([]);
  });
});
