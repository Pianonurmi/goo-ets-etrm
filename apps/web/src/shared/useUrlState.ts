import { useCallback } from 'react';
import { useSearchParams } from 'react-router';
import { oneOf } from './urlParams';

/** Changes to the query string: a value sets the key, a list repeats it, null or '' removes it, undefined keeps it. */
export type UrlPatch = Record<string, string | readonly string[] | null | undefined>;

/**
 * A screen's state kept in the URL, so it can be linked and survives a reload. Every change replaces the history
 * entry: filters and tabs are not steps the back button should walk through. One change per event: React Router
 * does not queue updates, so a second `set` in the same handler would start from the same query as the first.
 */
export function useUrlState() {
  const [params, setParams] = useSearchParams();
  const set = useCallback(
    (patch: UrlPatch) =>
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          Object.entries(patch).forEach(([key, value]) => {
            if (value === undefined) return;
            next.delete(key);
            if (typeof value === 'string') {
              if (value) next.set(key, value);
            } else if (value) {
              value.filter(Boolean).forEach((item) => next.append(key, item));
            }
          });
          return next;
        },
        { replace: true },
      ),
    [setParams],
  );
  /** Replaces the whole query string with [values]. */
  const reset = useCallback(
    (values: URLSearchParams | Record<string, string> = {}) => setParams(values, { replace: true }),
    [setParams],
  );
  return { params, set, reset };
}

/**
 * The open tab, kept as `?tab=` (absent for [fallback]). Switching tabs starts the query afresh: the filters belong
 * to the tab that was open.
 */
export function useUrlTab<T extends string>(tabs: readonly T[], fallback: T): [T, (tab: string | null) => void] {
  const { params, reset } = useUrlState();
  const tab = oneOf(params.get('tab'), tabs, fallback);
  const setTab = useCallback(
    (value: string | null) => reset(value && value !== fallback ? { tab: value } : {}),
    [reset, fallback],
  );
  return [tab, setTab];
}
