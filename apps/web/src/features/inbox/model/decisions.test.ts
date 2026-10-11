import {
  canCancel,
  canWorkOn,
  decisionBlock,
  decisionsOf,
  isOverdue,
  reasonRequired,
  type Task,
  type TaskType,
} from './decisions';

const type: TaskType = {
  taskType: 'TRADE_APPROVAL',
  displayName: 'Trade approval',
  ownerModule: 'trade',
  candidateRoles: ['MIDDLE_OFFICE'],
  fourEyes: true,
  allowedDecisions: ['APPROVE', 'REJECT'],
  reasonRequiredFor: ['REJECT'],
  escalationRoles: [],
  defaultPriority: 50,
  defaultSla: 'PT4H',
};

const task = {
  id: 't1',
  type: 'TRADE_APPROVAL',
  status: 'OPEN',
  summary: 'Approve GoO sale',
  subjectType: 'TRADE',
  subjectRef: 'trade-1',
  requestedBy: 'trader',
  requestedAt: '2026-10-01T08:00:00Z',
  dueAt: '2026-10-01T12:00:00Z',
  candidateRoles: ['MIDDLE_OFFICE'],
  priority: 50,
  escalationLevel: 0,
  payload: {},
  version: 1,
} as Task;

describe('decisions', () => {
  it('lets a candidate other than the requester decide', () => {
    expect(decisionBlock(task, type, { userId: 'middle', roles: ['MIDDLE_OFFICE'] })).toBeNull();
  });

  it('refuses the requester when four eyes apply, even an admin', () => {
    expect(decisionBlock(task, type, { userId: 'trader', roles: ['MIDDLE_OFFICE', 'TRADER'] })).toBe('own-request');
    expect(decisionBlock(task, type, { userId: 'trader', roles: ['ADMIN'] })).toBe('own-request');
    expect(
      decisionBlock(task, { ...type, fourEyes: false }, { userId: 'trader', roles: ['MIDDLE_OFFICE'] }),
    ).toBeNull();
  });

  it('refuses users without a candidate role, but not admins', () => {
    expect(decisionBlock(task, type, { userId: 'risk', roles: ['RISK'] })).toBe('not-candidate');
    expect(decisionBlock(task, type, { userId: 'boss', roles: ['ADMIN'] })).toBeNull();
  });

  it('treats decided tasks as closed', () => {
    expect(decisionBlock({ ...task, status: 'APPROVED' }, type, { userId: 'middle', roles: ['MIDDLE_OFFICE'] })).toBe(
      'closed',
    );
    expect(canWorkOn({ ...task, status: 'APPROVED' }, { userId: 'middle', roles: ['MIDDLE_OFFICE'] })).toBe(false);
  });

  it('lists allowed decisions in a stable order and knows which need a reason', () => {
    expect(decisionsOf({ ...type, allowedDecisions: ['REJECT', 'APPROVE'] })).toEqual(['APPROVE', 'REJECT']);
    expect(decisionsOf({ ...type, allowedDecisions: ['ACKNOWLEDGE'] })).toEqual(['ACKNOWLEDGE']);
    expect(reasonRequired(type, 'REJECT')).toBe(true);
    expect(reasonRequired(type, 'APPROVE')).toBe(false);
  });

  it('flags open tasks past their due time', () => {
    expect(isOverdue(task, new Date('2026-10-01T13:00:00Z'))).toBe(true);
    expect(isOverdue(task, new Date('2026-10-01T11:00:00Z'))).toBe(false);
    expect(isOverdue({ ...task, status: 'APPROVED' }, new Date('2026-10-02T00:00:00Z'))).toBe(false);
  });

  it('lets the requester, the assignee, an escalation role or an admin cancel an open task', () => {
    expect(canCancel(task, type, { userId: 'trader', roles: ['TRADER'] })).toBe(true);
    expect(canCancel(task, type, { userId: 'trader2', roles: ['TRADER'] })).toBe(false);
    expect(canCancel(task, type, { userId: 'middle', roles: ['MIDDLE_OFFICE'] })).toBe(false);
    expect(canCancel({ ...task, assignee: 'middle' }, type, { userId: 'middle', roles: ['MIDDLE_OFFICE'] })).toBe(true);
    expect(canCancel(task, { ...type, escalationRoles: ['RISK'] }, { userId: 'risk', roles: ['RISK'] })).toBe(true);
    expect(canCancel(task, type, { userId: 'boss', roles: ['ADMIN'] })).toBe(true);
    expect(canCancel({ ...task, status: 'CANCELLED' }, type, { userId: 'trader', roles: ['TRADER'] })).toBe(false);
    // Other approvals are decided, never withdrawn: their subject would wait forever.
    expect(canCancel({ ...task, type: 'POSTING_RULE' }, type, { userId: 'trader', roles: ['TRADER'] })).toBe(false);
  });
});
