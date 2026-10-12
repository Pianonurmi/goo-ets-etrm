import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData } from '@tanstack/react-query';
import { useMemo } from 'react';
import { $api } from '@/shared/api/client';
import type { HeadroomCheck, HeadroomResult } from './model/headroom';

/** Reference data the trade screens show by name; ids travel on the trades. */
export function useCounterparties() {
  const { data } = $api.useQuery('get', '/api/v1/reference/counterparties', {}, { staleTime: 5 * 60_000 });
  return useMemo(() => {
    const list = (data ?? []).filter((counterparty) => counterparty.active);
    return { list, byId: new Map((data ?? []).map((counterparty) => [counterparty.id, counterparty])) };
  }, [data]);
}

export function useBooks(entityId: string | undefined) {
  const { data } = $api.useQuery(
    'get',
    '/api/v1/reference/legal-entities/{id}/books',
    { params: { path: { id: entityId ?? '' } } },
    { enabled: Boolean(entityId), staleTime: 5 * 60_000 },
  );
  return useMemo(() => {
    const list = (data ?? []).filter((book) => book.active);
    return { list, byId: new Map((data ?? []).map((book) => [book.id, book])) };
  }, [data]);
}

export function useEntityAccounts(entityId: string | undefined) {
  const { data } = $api.useQuery(
    'get',
    '/api/v1/reference/registry-accounts',
    { params: { query: { holderType: 'LEGAL_ENTITY', holderId: entityId ?? '' } } },
    { enabled: Boolean(entityId), staleTime: 5 * 60_000 },
  );
  return useMemo(() => (data ?? []).filter((account) => account.active), [data]);
}

export function useAllowanceProducts() {
  const { data } = $api.useQuery('get', '/api/v1/products/allowances', {}, { staleTime: Infinity });
  return data ?? [];
}

/** Active futures contracts, optionally of one allowance product. */
export function useFuturesContracts(enabled: boolean) {
  const { data } = $api.useQuery(
    'get',
    '/api/v1/products/futures-contracts',
    { params: { query: { activeOnly: true } } },
    { enabled, staleTime: 5 * 60_000 },
  );
  return data ?? [];
}

/** Price indices an index-priced leg can refer to. */
export function usePriceIndices(enabled: boolean) {
  const { data } = $api.useQuery('get', '/api/v1/market-data/indices', {}, { enabled, staleTime: 5 * 60_000 });
  return useMemo(() => (data ?? []).filter((index) => index.active), [data]);
}

/**
 * How much of the form's sale deliveries the entity can still sell from what it holds (the same check the pre-trade
 * finding `GOO_HEADROOM` makes). Debounced while the user types; no result when the user may not ask, when nothing
 * is asked or when the call fails: the hint is advice and never stands in the way of saving.
 */
export function useHeadroom(request: HeadroomCheck | null, allowed: boolean): HeadroomResult | undefined {
  const key = request ? JSON.stringify(request) : '';
  const [debounced] = useDebouncedValue(key, 500);
  const body = useMemo(() => (debounced ? (JSON.parse(debounced) as HeadroomCheck) : undefined), [debounced]);
  const { data } = $api.useQuery(
    'post',
    '/api/v1/allocation/headroom/check',
    { body: body as HeadroomCheck },
    { enabled: allowed && body !== undefined, retry: false, staleTime: 30_000, placeholderData: keepPreviousData },
  );
  return allowed && key !== '' ? data : undefined;
}
