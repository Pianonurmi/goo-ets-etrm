import { StatusBadge } from '@/shared/components/StatusBadge';
import { type InstructionStatus } from './model/instructions';

export function InstructionStatusBadge({ status, testId }: { status: InstructionStatus; testId?: string }) {
  return <StatusBadge status={status} data-testid={testId} />;
}
