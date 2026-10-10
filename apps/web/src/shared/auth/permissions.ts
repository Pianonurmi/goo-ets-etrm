import type { OperationId, Role } from '@/shared/api/types';

/**
 * Roles that may call an operation, mirroring the services' role checks (ADR 0028). ADMIN may call everything;
 * an operation that is not listed is open to every signed-in user. The API stays the authority: this only decides
 * whether the UI offers an action.
 */
export const PERMISSIONS: Partial<Record<OperationId, readonly Role[]>> = {
  referenceData_createLegalEntity: ['ADMIN'],
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
