import { useQueryClient } from '@tanstack/react-query';
import { $api } from '@/shared/api/client';

export function useInvalidateLedger() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/ledger'),
    });
}

export function useAccounts(entityId: string | undefined) {
  return $api.useQuery(
    'get',
    '/api/v1/ledger/accounts',
    { params: { query: { legalEntityId: entityId ?? '' } } },
    { enabled: Boolean(entityId), staleTime: 5 * 60_000 },
  );
}
