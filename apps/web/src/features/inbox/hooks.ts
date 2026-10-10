import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';
import { $api } from '@/shared/api/client';
import type { TaskType } from './model/decisions';

/** Task type definitions, keyed by type; they change only with a deployment. */
export function useTaskTypes(): Map<string, TaskType> {
  const { data } = $api.useQuery('get', '/api/v1/workflow/task-types', {}, { staleTime: Infinity });
  return useMemo(() => new Map((data ?? []).map((type) => [type.taskType, type])), [data]);
}

/** Refreshes every workflow query (lists, counts, details) after a decision or assignment. */
export function invalidateWorkflow(queryClient: QueryClient): Promise<void> {
  return queryClient.invalidateQueries({
    predicate: (query) => typeof query.queryKey[1] === 'string' && query.queryKey[1].startsWith('/api/v1/workflow'),
  });
}

export function useInvalidateWorkflow(): () => Promise<void> {
  const queryClient = useQueryClient();
  return () => invalidateWorkflow(queryClient);
}

/** Open tasks the user can act on, per type; drives the navigation badge and the home page. */
export function useActionableCounts(): { total: number; byType: Record<string, number> } {
  const { data } = $api.useQuery(
    'get',
    '/api/v1/workflow/tasks/counts',
    { params: { query: { actionable: true } } },
    { refetchInterval: 60_000 },
  );
  return useMemo(() => {
    const byType = data ?? {};
    return { total: Object.values(byType).reduce((sum, count) => sum + count, 0), byType };
  }, [data]);
}
