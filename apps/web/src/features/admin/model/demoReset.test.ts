import { resetPhase } from './demoReset';

const requested = '2026-10-11T10:00:00Z';

describe('resetPhase', () => {
  it('follows the restart from the old process to the new data', () => {
    expect(resetPhase({ showcase: { resetRequestedAt: requested } }, requested)).toEqual({ kind: 'stopping' });
    expect(resetPhase(null, requested)).toEqual({ kind: 'starting' });
    expect(
      resetPhase(
        { demoSeed: { state: 'RUNNING', completedSteps: 4, stepCount: 29 }, showcase: { resetRequestedAt: null } },
        requested,
      ),
    ).toEqual({ kind: 'seeding', done: 4, total: 29 });
    expect(
      resetPhase(
        {
          demoSeed: { state: 'COMPLETED', completedSteps: 29, stepCount: 29, finishedAt: '2026-10-11T10:03:00Z' },
          showcase: {},
        },
        requested,
      ),
    ).toEqual({ kind: 'done' });
  });

  it('does not take the data from before the reset for the new one', () => {
    expect(
      resetPhase(
        {
          demoSeed: { state: 'COMPLETED', completedSteps: 29, stepCount: 29, finishedAt: '2026-10-11T04:20:00Z' },
          showcase: {},
        },
        requested,
      ),
    ).toEqual({ kind: 'starting' });
  });
});
