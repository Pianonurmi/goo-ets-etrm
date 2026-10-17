import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { Books } from './Books';
import { DevicesTab } from './DevicesTab';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('Books', () => {
  it("lists the entity's books and adds one", async () => {
    let created: unknown;
    server.use(
      http.get(`${ORIGIN}/api/v1/reference/legal-entities/e1/books`, () =>
        HttpResponse.json([
          { id: 'b1', legalEntityId: 'e1', code: 'DEMO-FI-TRD', name: 'Trading', bookType: 'TRADING' },
        ]),
      ),
      http.post(`${ORIGIN}/api/v1/reference/books`, async ({ request }) => {
        created = await request.json();
        return HttpResponse.json({ id: 'b2' });
      }),
    );
    const { unanswered } = renderPage(<Books entityId="e1" />, {
      url: '/admin/reference',
      route: '/admin/reference',
      roles: ['MIDDLE_OFFICE'],
    });

    expect(await screen.findByText('DEMO-FI-TRD')).toBeInTheDocument();
    expect(screen.getByText(/· Trading/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Add book' }));
    const dialog = await screen.findByRole('dialog');
    const save = within(dialog).getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Code' }), 'demo-fi-hdg');
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Name' }), 'Hedging');
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Book type' }));
    await userEvent.click(await screen.findByRole('option', { name: 'HEDGING' }));
    await userEvent.click(save);

    await waitFor(() =>
      expect(created).toEqual({ legalEntityId: 'e1', code: 'DEMO-FI-HDG', name: 'Hedging', bookType: 'HEDGING' }),
    );
    expect(unanswered()).toEqual([]);
  });

  it('says when the entity has no books, and offers adding one only to middle office', async () => {
    server.use(http.get(`${ORIGIN}/api/v1/reference/legal-entities/e1/books`, () => HttpResponse.json([])));
    renderPage(<Books entityId="e1" />, { url: '/admin/reference', route: '/admin/reference', roles: ['AUDITOR'] });

    expect(await screen.findByText('No books yet')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add book' })).toBeNull();
  });
});

describe('DevicesTab', () => {
  const device = {
    id: 'd1',
    gsrn: '643002480000000001',
    name: 'Koski hydro',
    country: 'FI',
    energySourceCode: 'HYDRO_RUN_OF_RIVER',
    technologyCode: 'HYDRO_TURBINE',
    capacityKw: 12500,
    commissioningDate: '1998-05-01',
    active: true,
    ownDevice: true,
    supportSchemeCodes: [],
    labelCodes: ['EKOENERGY'],
    ownerType: 'OWN',
    issuanceLagDays: 30,
    registryId: 'reg1',
    version: 4,
    externalCodes: { AIB: 'FI-123' },
  };

  it('lists our devices, all devices on request, and opens one with its facts', async () => {
    const own: (string | null)[] = [];
    server.use(
      http.get(`${ORIGIN}/api/v1/reference/production-devices`, ({ request }) => {
        own.push(new URL(request.url).searchParams.get('own'));
        return HttpResponse.json([device]);
      }),
      http.get(`${ORIGIN}/api/v1/reference/production-devices/d1`, () => HttpResponse.json(device)),
      http.get(`${ORIGIN}/api/v1/reference/registries`, () =>
        HttpResponse.json([{ id: 'reg1', code: 'FINGRID', name: 'Fingrid' }]),
      ),
      http.get(`${ORIGIN}/api/v1/reference/devices/d1/expected`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/reference/devices/d1/forecasts`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/reference/load-factors`, () => HttpResponse.json([])),
    );
    const { unanswered } = renderPage(<DevicesTab />, {
      url: '/admin/reference?tab=devices&open=d1',
      route: '/admin/reference',
      roles: ['BACK_OFFICE'],
    });

    await waitFor(() => expect(gridRowText('d1')).toContain('Koski hydro'));
    expect(gridRowText('d1')).toMatch(
      /^643002480000000001 \| Koski hydro \| FI \| HYDRO_RUN_OF_RIVER \| HYDRO_TURBINE \| 12500/,
    );
    expect(screen.getByTestId('new-device')).toBeInTheDocument();

    const drawer = await screen.findByTestId('device-drawer');
    expect(await within(drawer).findByTestId('device-fact-capacity')).toHaveTextContent('12,500');
    expect(within(drawer).getByTestId('device-fact-issuance-lag')).toHaveTextContent('30 days');
    expect(drawer).toHaveTextContent('Own device');
    expect(drawer).toHaveTextContent('EKOENERGY');
    expect(drawer).toHaveTextContent('AIB FI-123');
    await waitFor(() => expect(drawer).toHaveTextContent('FINGRID'));

    await userEvent.click(screen.getByRole('switch', { name: 'Own devices only' }));
    await waitFor(() => expect(own).toEqual(['true', 'false']));
    await waitFor(() => expect(unanswered()).toEqual([]));
  });
});
