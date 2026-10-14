import type { Task, TaskType } from './decisions';
import { decisionChecks, taskSteps, type TaskEvent } from './history';

const t = (key: string, options: Record<string, unknown> = {}) => {
  const rest = { ...options };
  delete rest.defaultValue;
  const base = key.replace('inbox.event.', '').replace('inbox.status.', '');
  return Object.keys(rest).length > 0 ? `${base}${JSON.stringify(rest)}` : base;
};

const task = {
  id: 't1',
  type: 'TRADE_APPROVAL',
  status: 'OPEN',
  requestedBy: 'trader',
  candidateRoles: ['MIDDLE_OFFICE', 'RISK'],
} as Task;

const event = (seq: number, eventType: string, actor: string, data: Record<string, unknown> = {}): TaskEvent => ({
  seq,
  taskId: 't1',
  eventType,
  actor,
  at: `2026-10-11T1${seq}:00:00Z`,
  data,
  hash: 'h',
});

describe('task history', () => {
  it('lists what happened and the decision still to come', () => {
    const steps = taskSteps(
      task,
      [event(1, 'OPENED', 'trader'), event(2, 'COMMENTED', 'middle'), event(3, 'CLAIMED', 'middle')],
      t,
    );
    expect(steps.map((step) => [step.label, step.state, step.by ?? null])).toEqual([
      ['OPENED', 'done', 'trader'],
      ['CLAIMED', 'done', 'middle'],
      ['awaiting', 'current', null],
    ]);
  });

  it('marks a rejection as failed, keeps the reason and keeps labels unique', () => {
    const closed = { ...task, status: 'REJECTED' } as Task;
    const steps = taskSteps(
      closed,
      [
        event(1, 'OPENED', 'trader'),
        event(2, 'CLAIMED', 'middle'),
        event(3, 'CLAIMED', 'middle2'),
        event(4, 'DECIDED', 'middle2', { decision: 'REJECT', reason: 'Wrong price' }),
      ],
      t,
    );
    expect(steps.map((step) => step.label)).toEqual(['OPENED', 'CLAIMED', 'CLAIMED (2)', 'DECIDED_REJECT']);
    expect(steps[3]).toMatchObject({ state: 'failed', detail: 'Wrong price' });
  });

  it('names a closed task by its final status', () => {
    const steps = taskSteps(
      { ...task, status: 'CANCELLED' },
      [event(1, 'CLOSED', 'trader', { status: 'CANCELLED', reason: 'Mistake' })],
      t,
    );
    expect(steps).toEqual([expect.objectContaining({ label: 'CANCELLED', state: 'done', detail: 'Mistake' })]);
  });
});

describe('decision checks', () => {
  const type = { fourEyes: true } as TaskType;

  it('checks the role and the second person', () => {
    expect(decisionChecks(task, type, { userId: 'middle', roles: ['MIDDLE_OFFICE'] })).toEqual([
      { key: 'role', ok: true },
      { key: 'fourEyes', ok: true },
    ]);
    expect(decisionChecks(task, type, { userId: 'trader', roles: ['TRADER'] })).toEqual([
      { key: 'role', ok: false },
      { key: 'fourEyes', ok: false },
    ]);
  });

  it('leaves out the second-person rule when the type has none', () => {
    expect(
      decisionChecks(task, { fourEyes: false } as TaskType, { userId: 'trader', roles: ['MIDDLE_OFFICE'] }),
    ).toEqual([{ key: 'role', ok: true }]);
  });
});
