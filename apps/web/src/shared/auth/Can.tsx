import type { ReactNode } from 'react';
import type { OperationId } from '@/shared/api/types';
import { can } from './permissions';
import { useSession } from './SessionContext';

/** Whether the signed-in user may call [operation]. */
export function useCan(operation: OperationId): boolean {
  return can(useSession().me.roles, operation);
}

/** Renders [children] only when the user may call [op]; otherwise [fallback]. */
export function Can({ op, children, fallback = null }: { op: OperationId; children: ReactNode; fallback?: ReactNode }) {
  return useCan(op) ? children : fallback;
}
