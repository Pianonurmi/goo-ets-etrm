import type { LifecycleStep, StepState } from '@/shared/components/LifecycleTimeline';

/** What a four-eyes record (curve snapshot, valuation run) shows of its lifecycle. */
export interface SignedOffRecord {
  status: 'DRAFT' | 'PENDING_SIGN_OFF' | 'OFFICIAL' | 'SUPERSEDED' | 'REJECTED';
  builtBy: string;
  builtAt: string;
  signedOffBy?: string | null;
  signedOffAt?: string | null;
  supersededAt?: string | null;
}

const REACHED: Record<SignedOffRecord['status'], number> = {
  DRAFT: 0,
  PENDING_SIGN_OFF: 1,
  OFFICIAL: 2,
  SUPERSEDED: 2,
  REJECTED: 2,
};

/**
 * Built → submitted for sign-off → official (signed off by a second person). A rejected record ends in a failed
 * step; a superseded one gets a last step for when a newer official version replaced it.
 */
export function signOffSteps(record: SignedOffRecord, t: (key: string) => string): LifecycleStep[] {
  const reached = REACHED[record.status];
  const state = (index: number): StepState =>
    index < reached || record.status === 'OFFICIAL' || record.status === 'SUPERSEDED'
      ? 'done'
      : index === reached
        ? 'current'
        : 'pending';
  const steps: LifecycleStep[] = [
    { label: t('curves.lifecycle.built'), state: state(0), by: record.builtBy, at: record.builtAt },
    { label: t('curves.lifecycle.submitted'), state: state(1) },
    record.status === 'REJECTED'
      ? { label: t('curves.lifecycle.rejected'), state: 'failed', by: record.signedOffBy, at: record.signedOffAt }
      : { label: t('curves.lifecycle.official'), state: state(2), by: record.signedOffBy, at: record.signedOffAt },
  ];
  if (record.status === 'SUPERSEDED') {
    steps.push({ label: t('curves.lifecycle.superseded'), state: 'done', at: record.supersededAt });
  }
  return steps;
}
