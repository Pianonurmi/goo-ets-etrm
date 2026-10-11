import { Badge, type BadgeProps } from '@mantine/core';
import { TONE_COLOR, type Tone } from '@/app/tokens';

/**
 * One colour per meaning across every lifecycle (ADR 0034): grey draft or ended, amber waiting for someone, blue in
 * progress, green done, red refused or broken. Unknown states fall back by their wording.
 */
const TONES: Record<string, Tone> = {
  DRAFT: 'neutral',
  CANCELLED: 'neutral',
  CLOSED: 'neutral',
  SUPERSEDED: 'neutral',
  WITHDRAWN: 'neutral',
  EXPIRED: 'neutral',
  INACTIVE: 'neutral',
  RELEASED: 'neutral',
  AUTO_CLEARED: 'neutral',
  OVERRIDDEN: 'neutral',
  UNPAID: 'waiting',
  PROPOSED: 'waiting',
  SOFT_CLOSED: 'waiting',
  INVESTIGATING: 'waiting',
  WARNING: 'waiting',
  DELAYED: 'waiting',
  OPEN: 'progress',
  APPROVED: 'progress',
  SUBMITTED: 'progress',
  EXECUTING: 'progress',
  ISSUED: 'progress',
  IN_TRANSFER: 'progress',
  RESERVED: 'progress',
  SENT: 'progress',
  DELIVERED_TO_GL: 'progress',
  PARTIALLY_DELIVERED: 'progress',
  PARTIALLY_PAID: 'progress',
  AGREED: 'progress',
  RUNNING: 'progress',
  CONFIRMED: 'done',
  DELIVERED: 'done',
  SETTLED: 'done',
  PAID: 'done',
  OFFICIAL: 'done',
  MATCHED: 'done',
  ACTIVE: 'done',
  APPLIED: 'done',
  COMPLETED: 'done',
  ACCEPTED: 'done',
  RESOLVED: 'done',
  RECONCILED: 'done',
  ACKNOWLEDGED: 'done',
  EXECUTED: 'done',
  HELD: 'done',
  OK: 'done',
  REJECTED: 'problem',
  FAILED: 'problem',
  DISPUTED: 'problem',
  MISMATCHED: 'problem',
  BREAKS: 'problem',
  BREACHED: 'problem',
  BREACH: 'problem',
  OVERDUE: 'problem',
  NEEDS_ATTENTION: 'problem',
  ERROR: 'problem',
  UNMATCHED: 'problem',
};

export function toneOf(status: string): Tone {
  const known = TONES[status];
  if (known) return known;
  if (/PENDING|AWAIT|WAITING/.test(status)) return 'waiting';
  if (/FAIL|REJECT|ERROR|BREAK|BREACH|OVERDUE/.test(status)) return 'problem';
  return 'neutral';
}

/** `PENDING_APPROVAL` → `Pending approval`. */
export function statusLabel(status: string): string {
  const words = status.replace(/_/g, ' ').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function StatusBadge({
  status,
  label,
  ...props
}: { status: string; label?: string } & Omit<BadgeProps, 'color' | 'children'> & { 'data-testid'?: string }) {
  return (
    <Badge
      color={TONE_COLOR[toneOf(status)]}
      variant="light"
      tt="none"
      radius="xl"
      leftSection={<span className="etrm-status-dot" aria-hidden="true" />}
      {...props}
    >
      {label ?? statusLabel(status)}
    </Badge>
  );
}
