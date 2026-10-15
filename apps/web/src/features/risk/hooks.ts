import { useQueries } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useBooks, useCounterparties } from '@/features/trades/hooks';
import { $api } from '@/shared/api/client';
import { type Limit, type ScopeNames } from './model/limits';
import { type Utilisation } from './shared';

export function useScopeNames(entityId: string | undefined): ScopeNames {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const books = useBooks(entityId);
  return useMemo(
    () => ({
      counterparty: (id: string) => counterparties.byId.get(id)?.name,
      book: (id: string) => books.byId.get(id)?.code,
      horizon: (days: number) => t('risk.horizonScope', { days }),
      vintage: (year: number) => t('risk.vintageScope', { year }),
    }),
    [counterparties.byId, books.byId, t],
  );
}

/** The latest measurement of each limit; a limit never measured answers 404 and shows as such. */
export function useUtilisations(limits: Limit[]): Map<string, Utilisation> {
  const results = useQueries({
    queries: limits.map((limit) =>
      $api.queryOptions(
        'get',
        '/api/v1/risk/limits/{id}/utilisation',
        { params: { path: { id: limit.id } } },
        { retry: false },
      ),
    ),
  });
  return new Map(
    results.flatMap((result, index) => {
      const limit = limits[index];
      return result.data && limit ? [[limit.id, result.data] as const] : [];
    }),
  );
}
