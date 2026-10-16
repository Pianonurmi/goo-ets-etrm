import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { Schema } from '@/shared/api/types';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { ReferenceDataPage } from './ReferenceDataPage';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const counterparty = (id: string, code: string, name: string, extra: object = {}) =>
  ({
    id,
    code,
    name,
    country: 'DE',
    counterpartyType: 'EXTERNAL',
    lei: '5299000J2N45DDNE4Y28',
    settlementCurrency: 'EUR',
    paymentTermsDays: 14,
    active: true,
    version: 3,
    masterAgreements: [{ type: 'EFET', reference: 'EFET-2024-07' }],
    nettingAgreed: true,
    ...extra,
  }) as unknown as Schema<'CounterpartyView'>;

const rheinstrom = counterparty('c1', 'CP-RHEIN', 'Rheinstrom AG');
const nordvind = counterparty('c2', 'CP-NORDVIND', 'Nordvind AS', { country: 'NO', active: false, lei: null });

describe('ReferenceDataPage', () => {
  it('lists the legal entities in the grid', async () => {
    const { unanswered } = renderPage(<ReferenceDataPage />, {
      url: '/admin/reference',
      route: '/admin/reference',
      roles: ['MIDDLE_OFFICE'],
    });
    await screen.findByRole('gridcell', { name: 'Demo Energia Oy' });
    expect(gridRowText('e1')).toMatch(/^DEMO-FI \| Demo Energia Oy \| FI \| EUR/);
    expect(unanswered()).toEqual([]);
  });

  it('lists the counterparties, active and inactive, and opens a linked one in its drawer', async () => {
    server.use(
      http.get(`${ORIGIN}/api/v1/reference/counterparties`, () => HttpResponse.json([rheinstrom, nordvind])),
      http.get(`${ORIGIN}/api/v1/reference/counterparties/c1`, () => HttpResponse.json(rheinstrom)),
      http.get(`${ORIGIN}/api/v1/reference/parties/COUNTERPARTY/c1/addresses`, () =>
        HttpResponse.json([
          {
            partyType: 'COUNTERPARTY',
            partyId: 'c1',
            addressType: 'REGISTERED',
            line1: 'Rheinufer 12',
            postalCode: '40213',
            city: 'Düsseldorf',
            country: 'DE',
            text: 'Rheinufer 12, 40213 Düsseldorf, DE',
          },
        ]),
      ),
      http.get(`${ORIGIN}/api/v1/reference/parties/COUNTERPARTY/c1/vat-registrations`, () => HttpResponse.json([])),
      http.get(`${ORIGIN}/api/v1/reference/parties/COUNTERPARTY/c1/bank-accounts`, () =>
        HttpResponse.json([
          {
            id: 'b1',
            partyType: 'COUNTERPARTY',
            partyId: 'c1',
            accountName: 'Rheinstrom AG',
            iban: 'DE89370400440532013000',
            bic: 'COBADEFFXXX',
            currency: 'EUR',
            active: true,
            version: 1,
          },
        ]),
      ),
      http.get(`${ORIGIN}/api/v1/reference/registries`, () =>
        HttpResponse.json([
          {
            id: 'r1',
            code: 'DE-HKNR',
            name: 'Herkunftsnachweisregister',
            registryType: 'GOO',
            integration: 'MANUAL',
            hubMember: true,
            active: true,
            version: 1,
          },
        ]),
      ),
      http.get(`${ORIGIN}/api/v1/reference/registry-accounts`, () =>
        HttpResponse.json([
          {
            id: 'ra1',
            registryId: 'r1',
            registryCode: 'DE-HKNR',
            holderType: 'COUNTERPARTY',
            holderId: 'c1',
            accountNumber: 'DE-0042',
            name: 'Rheinstrom trading',
            accountType: 'TRADING',
            canImport: true,
            canExport: true,
            trusted: true,
            active: true,
            version: 1,
          },
        ]),
      ),
    );
    const { unanswered } = renderPage(<ReferenceDataPage />, {
      url: '/admin/reference?tab=counterparties&open=c1',
      route: '/admin/reference',
      roles: ['MIDDLE_OFFICE'],
    });

    await screen.findByRole('gridcell', { name: 'Rheinstrom AG' });
    expect(gridRowText('c1')).toBe('CP-RHEIN | Rheinstrom AG | External | DE | 5299000J2N45DDNE4Y28 | EUR | Active');
    expect(gridRowText('c2')).toBe('CP-NORDVIND | Nordvind AS | External | NO |  | EUR | Inactive');

    const drawer = await screen.findByTestId('counterparty-drawer');
    expect(await within(drawer).findByText(/External · DE · EUR/)).toBeInTheDocument();
    expect(within(drawer).getByText(/EFET-2024-07/)).toBeInTheDocument();
    expect(await within(drawer).findByText('Rheinufer 12, 40213 Düsseldorf, DE')).toBeInTheDocument();
    expect(await within(drawer).findByText(/DE89370400440532013000|DE89 3704 0044 0532 0130 00/)).toBeInTheDocument();
    expect(await within(drawer).findByText('DE-HKNR:DE-0042')).toBeInTheDocument();
    await waitFor(() => expect(unanswered()).toEqual([]));
  });
});
