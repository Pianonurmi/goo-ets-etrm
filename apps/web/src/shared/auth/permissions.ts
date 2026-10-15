import type { OperationId, Role } from '@/shared/api/types';
import { GENERATED_PERMISSIONS } from './permissions.generated';

/**
 * Roles that may call an operation: the services' role checks, published by the API as `x-roles` (`@AllowedRoles` on
 * each handler, ADR 0027) and generated into `permissions.generated.ts` by `npm run gen:api`. ADMIN may call
 * everything; an operation that is not listed is open to every signed-in user. The API stays the authority: this
 * only decides whether the UI offers an action.
 */
export const PERMISSIONS = GENERATED_PERMISSIONS;

export function can(roles: readonly Role[], operation: OperationId): boolean {
  if (roles.includes('ADMIN')) return true;
  const allowed = PERMISSIONS[operation];
  return allowed === undefined || allowed.some((role) => roles.includes(role));
}
