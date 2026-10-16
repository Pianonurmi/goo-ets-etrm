import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { ORIGIN, server } from '@/test/server';
import { PausedRegistries } from './PausedRegistries';

vi.mock('@/shared/auth/SessionContext', () => ({
  useSession: () => ({ me: { roles: ['REGISTRY_OPERATOR'] } }),
}));

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const circuit = {
  registryCode: 'CESAR',
  paused: true,
  consecutiveFailures: 5,
  lastFailure: 'connection refused',
  pausedSince: '2026-10-16T10:00:00Z',
  probeAfter: '2026-10-16T10:05:00Z',
};

function renderBanner() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <PausedRegistries />
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('PausedRegistries', () => {
  it('shows paused registries only, and resumes one', async () => {
    let resumed: string | null = null;
    server.use(
      http.get(`${ORIGIN}/api/v1/registry/circuits`, () =>
        HttpResponse.json([circuit, { ...circuit, registryCode: 'FINGRID', paused: false, consecutiveFailures: 2 }]),
      ),
      http.post(`${ORIGIN}/api/v1/registry/circuits/:code/resume`, ({ params }) => {
        resumed = String(params.code);
        return HttpResponse.json({ ...circuit, paused: false, consecutiveFailures: 0 });
      }),
    );
    renderBanner();
    expect(await screen.findByTestId('paused-registry-CESAR')).toHaveTextContent('connection refused');
    expect(screen.queryByTestId('paused-registry-FINGRID')).toBeNull();
    await userEvent.click(screen.getByTestId('resume-registry-CESAR'));
    await waitFor(() => expect(resumed).toBe('CESAR'));
  });

  it('shows nothing while no registry is paused', async () => {
    let asked = false;
    server.use(
      http.get(`${ORIGIN}/api/v1/registry/circuits`, () => {
        asked = true;
        return HttpResponse.json([]);
      }),
    );
    renderBanner();
    await waitFor(() => expect(asked).toBe(true));
    expect(screen.queryByTestId(/^paused-registry-/)).toBeNull();
    expect(screen.queryByRole('button')).toBeNull();
  });
});
