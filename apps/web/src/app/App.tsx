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

/**
 * Queries show their own error state; mutations notify unless they set `meta: { notify: false }` (forms map
 * violations onto fields instead). 401s are handled by the sign-in flow.
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
        refetchOnWindowFocus: false,
        retry: (count, error) => asProblemError(error).status >= 500 && count < 2,
      },
    },
  });
}

function SessionGate({ config }: { config: UiConfig }) {
  const me = $api.useQuery('get', '/api/v1/me', {}, { staleTime: Infinity });
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
