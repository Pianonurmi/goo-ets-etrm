import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import type { Schema } from '@/shared/api/types';
import { ORIGIN, server } from '@/test/server';
import { EditEntityModal } from './ReferenceEditModals';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const base = {
  id: 'e1',
  code: 'DEMO-FI',
  name: 'Demo Energia Oy',
  country: 'FI',
  baseCurrency: 'EUR',
  timeZone: 'Europe/Helsinki',
  lei: '5493001KJTIIGC8Y1R12',
  vatNumber: 'FI12345678',
  emirClassification: 'NFC_MINUS',
  obligationsOpenAt: 'APPROVED',
  active: true,
  validPeriod: { validFrom: '2026-01-01' },
};
const loaded = { ...base, version: 1 } as unknown as Schema<'LegalEntityView'>;
// Someone else renamed the entity and gave it an end date while the modal was open.
const current = {
  ...base,
  name: 'Demo Energia Ab',
  version: 2,
  validPeriod: { validFrom: '2026-01-01', validTo: '2030-01-01' },
};

function renderModal() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <EditEntityModal entity={loaded} onClose={() => undefined} />
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('EditEntityModal after a stale save', () => {
  it('shows what changed, keeps the edits and saves against the current version', async () => {
    const bodies: Record<string, unknown>[] = [];
    server.use(
      http.patch(`${ORIGIN}/api/v1/reference/legal-entities/e1`, async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>;
        bodies.push(body);
        if (bodies.length === 1) {
          return HttpResponse.json(
            { type: 'urn:etrm:problem:stale-version', title: 'stale-version', status: 409, detail: 'changed' },
            { status: 409 },
          );
        }
        return HttpResponse.json({ ...current, ...body });
      }),
      http.get(`${ORIGIN}/api/v1/reference/legal-entities/e1`, () => HttpResponse.json(current)),
    );
    const user = userEvent.setup();
    renderModal();

    await user.clear(await screen.findByTestId('edit-vat'));
    await user.type(screen.getByTestId('edit-vat'), 'FI99999999');
    await user.click(screen.getByTestId('edit-save'));

    const alert = await screen.findByTestId('stale-version-alert');
    expect(alert).toHaveTextContent('Name: was Demo Energia Oy, now Demo Energia Ab.');
    expect(alert).toHaveTextContent('Valid to: was —, now 2030-01-01.');
    expect(alert).not.toHaveTextContent('Your edit');
    expect(screen.queryByTestId('problem-alert')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('stale-keep-edits'));
    expect(screen.queryByTestId('stale-version-alert')).not.toBeInTheDocument();
    expect(screen.getByTestId('edit-vat')).toHaveValue('FI99999999');
    expect(screen.getByTestId('edit-name')).toHaveValue('Demo Energia Ab');
    expect(screen.getByTestId('edit-valid-to')).toHaveValue('2030-01-01');

    await user.click(screen.getByTestId('edit-save'));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]).toEqual({ expectedVersion: 2, vatNumber: 'FI99999999' });
  });

  it('can drop the edits and start from the current version', async () => {
    server.use(
      http.patch(`${ORIGIN}/api/v1/reference/legal-entities/e1`, () =>
        HttpResponse.json(
          { type: 'urn:etrm:problem:stale-version', title: 'stale-version', status: 409, detail: 'changed' },
          { status: 409 },
        ),
      ),
      http.get(`${ORIGIN}/api/v1/reference/legal-entities/e1`, () => HttpResponse.json(current)),
    );
    const user = userEvent.setup();
    renderModal();

    await user.clear(await screen.findByTestId('edit-name'));
    await user.type(screen.getByTestId('edit-name'), 'My name');
    await user.click(screen.getByTestId('edit-save'));
    expect(await screen.findByTestId('stale-version-alert')).toHaveTextContent(
      'Name: was Demo Energia Oy, now Demo Energia Ab. Your edit (My name) is kept.',
    );

    await user.click(screen.getByTestId('stale-discard-edits'));
    expect(screen.getByTestId('edit-name')).toHaveValue('Demo Energia Ab');
    expect(screen.getByTestId('edit-save')).toBeDisabled();
  });
});
