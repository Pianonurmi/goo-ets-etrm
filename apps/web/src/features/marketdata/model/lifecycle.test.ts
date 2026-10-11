import { describe, expect, it } from 'vitest';
import { signOffSteps, type SignedOffRecord } from './lifecycle';

const t = (key: string) => key.replace('curves.lifecycle.', '');
const built = { builtBy: 'risk', builtAt: '2026-10-09T17:00:00Z' };
const states = (record: SignedOffRecord) => signOffSteps(record, t).map((step) => `${step.label}:${step.state}`);

describe('signOffSteps', () => {
  it('sits on the draft, then waits for the sign-off', () => {
    expect(states({ ...built, status: 'DRAFT' })).toEqual(['built:current', 'submitted:pending', 'official:pending']);
    expect(states({ ...built, status: 'PENDING_SIGN_OFF' })).toEqual([
      'built:done',
      'submitted:current',
      'official:pending',
    ]);
  });

  it('is done once official, naming the second person', () => {
    const steps = signOffSteps(
      { ...built, status: 'OFFICIAL', signedOffBy: 'risk2', signedOffAt: '2026-10-09T18:00:00Z' },
      t,
    );
    expect(steps.map((step) => step.state)).toEqual(['done', 'done', 'done']);
    expect(steps[2]).toMatchObject({ by: 'risk2', at: '2026-10-09T18:00:00Z' });
    expect(steps[0]).toMatchObject({ by: 'risk', at: built.builtAt });
  });

  it('ends a rejected record in a failed step and adds the replacement of a superseded one', () => {
    expect(states({ ...built, status: 'REJECTED' })).toEqual(['built:done', 'submitted:done', 'rejected:failed']);
    expect(states({ ...built, status: 'SUPERSEDED', supersededAt: '2026-10-10T08:00:00Z' })).toEqual([
      'built:done',
      'submitted:done',
      'official:done',
      'superseded:done',
    ]);
  });
});
