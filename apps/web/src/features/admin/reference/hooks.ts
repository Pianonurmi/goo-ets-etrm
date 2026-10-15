import { $api } from '@/shared/api/client';
import { useUrlState } from '@/shared/useUrlState';

export function useRegistries() {
  return $api.useQuery('get', '/api/v1/reference/registries', {}, { staleTime: 5 * 60_000 });
}

/** The record whose drawer is open, kept in the URL (`?open=<id>`) so it can be linked and survives a reload. */
export function useOpenRecord(): [string | null, (id: string | null) => void] {
  const { params, set: patchUrl } = useUrlState();
  const set = (id: string | null) => patchUrl({ open: id });
  return [params.get('open'), set];
}
