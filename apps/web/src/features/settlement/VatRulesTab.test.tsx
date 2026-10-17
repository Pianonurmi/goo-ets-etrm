import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { VatRulesTab } from './VatRulesTab';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const rule = {
  id: 'r1',
  code: 'FI-GOO-DOM',
  version: 2,
  status: 'APPROVED',
  description: 'Domestic GoO sale, standard rate',
  productFamily: 'GUARANTEE_OF_ORIGIN',
  supplierCountry: 'FI',
  scope: 'DOMESTIC',
  treatment: 'STANDARD',
  rate: 25.5,
  validFrom: '2026-01-01',
  createdBy: 'accountant',
  approvedBy: 'controller',
  legalText: 'AVL 1 §',
};

describe('VatRulesTab', () => {
  it('lists the rules, retired ones on request, and proposes a new rule for approval', async () => {
    const includeRetired: (string | null)[] = [];
    let proposed: Record<string, unknown> | undefined;
    server.use(
      http.get(`${ORIGIN}/api/v1/settlement/vat-rules`, ({ request }) => {
        includeRetired.push(new URL(request.url).searchParams.get('includeRetired'));
        return HttpResponse.json([rule, { ...rule, id: 'r0', productFamily: '*', version: 1, status: 'RETIRED' }]);
      }),
      http.post(`${ORIGIN}/api/v1/settlement/vat-rules`, async ({ request }) => {
        proposed = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json({ ...rule, id: 'r2', status: 'DRAFT' });
      }),
    );
    const { unanswered } = renderPage(<VatRulesTab />, {
      url: '/settlement?tab=vat-rules',
      route: '/settlement',
      roles: ['ACCOUNTANT'],
    });

    await waitFor(() => expect(gridRowText('r1')).toContain('FI-GOO-DOM'));
    expect(gridRowText('r1')).toMatch(
      /^FI-GOO-DOM \| 2 \| APPROVED \| Domestic GoO sale, standard rate \| Guarantee.* \| FI \| Domestic \| Standard rate \| 25\.5 % \| 2026-01-01/,
    );
    expect(gridRowText('r0')).toContain('Any product');
    await userEvent.click(screen.getByRole('switch', { name: 'Include retired rules' }));
    await waitFor(() => expect(includeRetired).toEqual(['false', 'true']));

    await userEvent.click(screen.getByTestId('propose-rule'));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByTestId('rule-submit')).toBeDisabled();
    await userEvent.type(within(dialog).getByTestId('rule-code'), 'se-goo-exp');
    await userEvent.type(within(dialog).getByTestId('rule-description'), 'GoO sold outside the EU');
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Scope' }));
    await userEvent.click(await screen.findByRole('option', { name: 'To outside the EU' }));
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'VAT treatment' }));
    await userEvent.click(await screen.findByRole('option', { name: 'Outside the scope of VAT' }));
    await userEvent.click(within(dialog).getByTestId('rule-submit'));

    await waitFor(() => expect(proposed).toBeDefined());
    expect(proposed).toMatchObject({
      code: 'SE-GOO-EXP',
      description: 'GoO sold outside the EU',
      productFamily: '*',
      supplierCountry: '*',
      scope: 'EXPORT',
      treatment: 'OUT_OF_SCOPE',
      rate: '0',
    });
    expect(unanswered()).toEqual([]);
  });

  it('tells which rule applies to a supply, or why none does', async () => {
    const asked: string[] = [];
    server.use(
      http.get(`${ORIGIN}/api/v1/settlement/vat-rules`, () => HttpResponse.json([rule])),
      http.get(`${ORIGIN}/api/v1/settlement/vat-rules/determination`, ({ request }) => {
        const query = new URL(request.url).searchParams;
        asked.push(`${query.get('productFamily')} ${query.get('supplierCountry')}>${query.get('customerCountry')}`);
        return HttpResponse.json(
          query.get('customerCountry') === 'FI'
            ? { rule }
            : { rule: null, problem: 'No VAT rule for a GoO supply from FI to US' },
        );
      }),
    );
    const { unanswered } = renderPage(<VatRulesTab />, {
      url: '/settlement?tab=vat-rules',
      route: '/settlement',
      roles: ['ACCOUNTANT'],
    });

    const customer = await screen.findByTestId('determine-customer');
    await userEvent.clear(customer);
    await userEvent.type(customer, 'fi');
    await userEvent.click(screen.getByTestId('determine'));
    expect(await screen.findByTestId('determination-result')).toHaveTextContent('FI-GOO-DOM v2: Standard rate, 25.5 %');
    expect(screen.getByTestId('determination-result')).toHaveTextContent('AVL 1 §');

    await userEvent.clear(customer);
    await userEvent.type(customer, 'us');
    await userEvent.click(screen.getByTestId('determine'));
    await waitFor(() =>
      expect(screen.getByTestId('determination-result')).toHaveTextContent('No VAT rule for a GoO supply'),
    );
    expect(asked).toContain('GUARANTEE_OF_ORIGIN FI>FI');
    expect(asked).toContain('GUARANTEE_OF_ORIGIN FI>US');
    expect(unanswered()).toEqual([]);
  });
});
