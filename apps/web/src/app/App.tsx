import { Center, Loader } from '@mantine/core';
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { RouterProvider } from 'react-router';
import { $api } from '@/shared/api/client';
import { asProblemError } from '@/shared/api/problem';
import type { UiConfig } from '@/shared/auth/session';
import { SessionContext } from '@/shared/auth/SessionContext';
import { notifyProblem } from '@/shared/components/notify';
import { QueryState } from '@/shared/components/QueryState';
import { EntityProvider } from '@/shared/context/EntityContext';
import { router } from './router';

/** A server error or a network failure (status 0) is worth another try; anything else would fail the same way. */
export function shouldRetry(count: number, error: unknown): boolean {
  const status = asProblemError(error).status;
  return (status >= 500 || status === 0) && count < 2;
}

/**
 * Queries show their own error state; mutations notify unless they set `meta: { notify: false }` (forms map
 * violations onto fields instead). 401s are handled by the sign-in flow. Queries older than their stale time are
 * fetched again when the window regains focus; network failures and 5xx are retried twice.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    queryCache: new QueryCache(),
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (mutation.meta?.notify === false || asProblemError(error).status === 401) return;
        notifyProblem(error);
      },
    }),
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: true,
        retry: shouldRetry,
      },
    },
  });
}

function SessionGate({ config }: { config: UiConfig }) {
  // Fetched again whenever the window regains focus, so a role change applies without a reload.
  const me = $api.useQuery('get', '/api/v1/me', {}, { staleTime: 0, refetchOnWindowFocus: 'always' });
  if (!me.data) {
    return (
      <Center h="100vh">
        {me.error ? (
          <QueryState isPending={false} error={me.error}>
            {null}
          </QueryState>
        ) : (
          <Loader />
        )}
      </Center>
    );
  }
  return (
    <SessionContext value={{ config, me: me.data }}>
      <EntityProvider>
        <RouterProvider router={router} />
      </EntityProvider>
    </SessionContext>
  );
}

export function App({ config }: { config: UiConfig }) {
  const [queryClient] = useState(createQueryClient);
  return (
    <QueryClientProvider client={queryClient}>
      <SessionGate config={config} />
    </QueryClientProvider>
  );
}
