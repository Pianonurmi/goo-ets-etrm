import { useQueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';
import { $api } from '@/shared/api/client';
import { useCounterparties } from '@/features/trades/hooks';
import type { Account } from './model/clearing';

/** Clearing changes also open and close inbox tasks and move ledger figures, so those are refetched too. */
export function useInvalidateClearing() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        ['/api/v1/clearing', '/api/v1/workflow'].some((prefix) => (q.queryKey[1] as string).startsWith(prefix)),
    });
}

/** The entity's clearing accounts (a short list, kept for a few minutes). */
export function useClearingAccounts(entityId: string | undefined) {
  return $api.useQuery(
    'get',
    '/api/v1/clearing/accounts',
    { params: { query: { legalEntityId: entityId } } },
    { enabled: Boolean(entityId), staleTime: 60_000 },
  );
}

/** The counterparties of type CLEARING_HOUSE, and every counterparty by id for showing names. */
export function useClearingHouses() {
  const counterparties = useCounterparties();
  return useMemo(
    () => ({
      houses: counterparties.list.filter((counterparty) => counterparty.counterpartyType === 'CLEARING_HOUSE'),
      byId: counterparties.byId,
    }),
    [counterparties],
  );
}

/** `CODE · Clearing house` for an account, as the pickers and tables show it. */
export function accountLabel(account: Account, houseName: string | undefined): string {
  return houseName ? `${account.code} · ${houseName}` : account.code;
}
