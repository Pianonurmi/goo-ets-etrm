import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { configure, render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { Role, Schema } from '@/shared/api/types';
import { SessionContext } from '@/shared/auth/SessionContext';
import { EntityProvider } from '@/shared/context/EntityContext';
import { server } from './server';

// A whole page with AG Grid takes seconds to mount on a loaded CI runner under coverage; the default 1 s wait of
// `findBy…`/`waitFor` is too short there. Only the page tests import this module.
configure({ asyncUtilTimeout: 10_000 });

/**
 * Renders a whole page as the app does (session, selected entity, router, query client) at `url`, matched by `route`.
 * The page talks to msw; every request it makes must be answered by a handler, so a page test also shows which calls
 * the page depends on. Call `unanswered()` at the end of a test: it lists the requests no handler took.
 */
export function renderPage(
  element: ReactElement,
  { url, route, roles }: { url: string; route: string; roles: Role[] },
): { unanswered: () => string[] } {
  const unanswered: string[] = [];
  server.events.removeAllListeners('request:unhandled');
  server.events.on('request:unhandled', ({ request }) => {
    const target = new URL(request.url);
    unanswered.push(`${request.method} ${target.pathname}`);
  });
  const me = {
    userId: 'tester',
    displayName: 'Tester',
    roles,
    legalEntityIds: [],
    allEntities: true,
    authMode: 'DEV_HEADERS',
  } as unknown as Schema<'MeView'>;
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <SessionContext
          value={{ config: { authMode: 'DEV_HEADERS', environment: 'TEST', version: 'dev', demo: false }, me }}
        >
          <EntityProvider>
            <MemoryRouter initialEntries={[url]}>
              <Routes>
                <Route path={route} element={element} />
                <Route path="*" element={<div data-testid="navigated-away" />} />
              </Routes>
            </MemoryRouter>
          </EntityProvider>
        </SessionContext>
      </QueryClientProvider>
    </MantineProvider>,
  );
  return { unanswered: () => [...new Set(unanswered)] };
}

/**
 * The text of a grid row by its row id, across AG Grid's pinned and centre containers (each holds part of the row).
 * Cells are joined with " | ".
 */
export function gridRowText(rowId: string): string {
  return [...document.querySelectorAll(`[row-id="${rowId}"] [role="gridcell"]`)]
    .map((cell) => cell.textContent?.trim() ?? '')
    .join(' | ');
}
