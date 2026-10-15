import { useQueryClient } from '@tanstack/react-query';

export function useRefreshMarketData() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) => typeof q.queryKey[1] === 'string' && q.queryKey[1].startsWith('/api/v1/market-data'),
    });
}
