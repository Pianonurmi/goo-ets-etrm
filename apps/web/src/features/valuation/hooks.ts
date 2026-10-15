import dayjs from 'dayjs';
import { $api } from '@/shared/api/client';

export function useRecentRuns(entityId: string) {
  return $api.useQuery('get', '/api/v1/valuation/runs', {
    params: {
      query: {
        legalEntityId: entityId,
        from: dayjs().subtract(90, 'day').format('YYYY-MM-DD'),
        to: dayjs().add(1, 'day').format('YYYY-MM-DD'),
      },
    },
  });
}

/** The two latest official runs, oldest first: the natural default for a P&L explain. */
export function useLatestOfficialDates(entityId: string): { dates: [string, string] | null; ready: boolean } {
  const runs = $api.useQuery('get', '/api/v1/valuation/runs', {
    params: {
      query: {
        legalEntityId: entityId,
        from: dayjs().subtract(120, 'day').format('YYYY-MM-DD'),
        to: dayjs().add(1, 'day').format('YYYY-MM-DD'),
      },
    },
  });
  const dates = [
    ...new Set((runs.data ?? []).filter((run) => run.status === 'OFFICIAL').map((run) => run.valuationDate)),
  ].sort();
  const to = dates[dates.length - 1];
  const from = dates[dates.length - 2];
  return { dates: from && to ? [from, to] : null, ready: !runs.isPending };
}
