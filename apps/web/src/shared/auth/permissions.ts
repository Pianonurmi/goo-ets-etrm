import type { OperationId, Role } from '@/shared/api/types';

/**
 * Roles that may call an operation, mirroring the services' role checks (ADR 0028). ADMIN may call everything;
 * an operation that is not listed is open to every signed-in user. The API stays the authority: this only decides
 * whether the UI offers an action.
 */
export const PERMISSIONS: Partial<Record<OperationId, readonly Role[]>> = {
  referenceData_createLegalEntity: ['ADMIN'],
  trade_capture: ['TRADER', 'MIDDLE_OFFICE'],
  trade_amend: ['TRADER', 'MIDDLE_OFFICE'],
  trade_cancel: ['TRADER', 'MIDDLE_OFFICE'],
  trade_approve: ['MIDDLE_OFFICE', 'RISK'],
  trade_reject: ['MIDDLE_OFFICE', 'RISK'],
  trade_confirm: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  trade_recordDelivery: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  trade_settle: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  confirmation_issue: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_propose: ['TRADER', 'MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_whatIf: ['TRADER', 'MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_approve: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_reject: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_cancel: ['TRADER', 'MIDDLE_OFFICE', 'BACK_OFFICE'],
  inventory_receive: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_split: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_reserve: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_release: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_cancel: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_withdraw: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_correct: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
};

export function can(roles: readonly Role[], operation: OperationId): boolean {
  if (roles.includes('ADMIN')) return true;
  const allowed = PERMISSIONS[operation];
  return allowed === undefined || allowed.some((role) => roles.includes(role));
}
