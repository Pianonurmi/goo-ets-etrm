import { useQueryClient } from '@tanstack/react-query';

/** Settlement changes also open and close inbox tasks, so both are refetched. */
export function useInvalidateSettlement() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        (q.queryKey[1].startsWith('/api/v1/settlement') || q.queryKey[1].startsWith('/api/v1/workflow')),
    });
}
