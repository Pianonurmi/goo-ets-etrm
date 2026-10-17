import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { vi } from 'vitest';
import type { LegalEntity } from '@/shared/context/EntityContext';
import { gridRowText, renderPage } from '@/test/renderPage';
import { ORIGIN, server } from '@/test/server';
import { AccountsTab } from './AccountsTab';
import type { Account, Posted } from './model/clearing';

/** Types [text] into [input] in one go: per-key typing re-renders the form for every character. */
async function fill(input: HTMLElement, text: string) {
  await userEvent.click(input);
  await userEvent.paste(text);
}

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const entity = { id: 'e1', code: 'DEMO-FI', name: 'Demo Energia Oy', baseCurrency: 'EUR' } as LegalEntity;

const account = {
  id: 'a1',
  code: 'ECC-DEMO-FI',
  legalEntityId: 'e1',
  clearingHouseId: 'ch1',
  clearingMember: 'Hanse Clearing Bank AG',
  currency: 'EUR',
  initialMargin: 250000,
  active: true,
  createdAt: '2026-01-05T08:00:00Z',
  createdBy: 'backoffice',
} as Account;

const counterparties = http.get(`${ORIGIN}/api/v1/reference/counterparties`, () =>
  HttpResponse.json([
    {
      id: 'ch1',
      code: 'CP-CLEARING',
      name: 'Continental Clearing House AG',
      counterpartyType: 'CLEARING_HOUSE',
      active: true,
    },
    { id: 'ch2', code: 'CP-NORDIC-CCP', name: 'Nordic CCP', counterpartyType: 'CLEARING_HOUSE', active: true },
    { id: 'c1', code: 'CP-RHEIN', name: 'Rheinstrom AG', counterpartyType: 'EXTERNAL', active: true },
  ]),
);

describe('AccountsTab', () => {
  it('lists the accounts with their margin, cash and open calls, and selects one on a click', async () => {
    const onSelect = vi.fn();
    server.use(counterparties);
    const posted = [{ accountId: 'a1', cashBalance: 248750, openCalls: 1250 }] as unknown as Posted[];
    const { unanswered } = renderPage(
      <AccountsTab
        entity={entity}
        accounts={[account]}
        loading={false}
        posted={posted}
        selectedId={null}
        onSelect={onSelect}
      />,
      { url: '/clearing', route: '/clearing', roles: ['BACK_OFFICE'] },
    );

    await waitFor(() => expect(gridRowText('a1')).toContain('Continental Clearing House AG'));
    expect(gridRowText('a1')).toMatch(
      /^ECC-DEMO-FI \| Continental Clearing House AG \| Hanse Clearing Bank AG \| EUR \| 250,000\.00 EUR \| 248,750\.00 EUR \| 1,250\.00 EUR \| Active/,
    );
    await userEvent.click(screen.getByRole('gridcell', { name: 'ECC-DEMO-FI' }));
    expect(onSelect).toHaveBeenCalledWith('a1');
    expect(unanswered()).toEqual([]);
  });

  it('says when the entity has no account yet', async () => {
    server.use(counterparties);
    renderPage(
      <AccountsTab
        entity={entity}
        accounts={[]}
        loading={false}
        posted={[]}
        selectedId={null}
        onSelect={() => undefined}
      />,
      { url: '/clearing', route: '/clearing', roles: ['TRADER'] },
    );

    expect(await screen.findByTestId('clearing-accounts-empty')).toHaveTextContent('No clearing account yet');
    expect(screen.queryByTestId('clearing-new-account')).toBeNull();
  });

  it('opens an account at a clearing house, and puts a refused field on its input', async () => {
    const onSelect = vi.fn();
    const bodies: unknown[] = [];
    server.use(
      counterparties,
      http.post(`${ORIGIN}/api/v1/clearing/accounts`, async ({ request }) => {
        const body = (await request.json()) as { code: string };
        bodies.push(body);
        if (body.code === 'ECC-DEMO-FI')
          return HttpResponse.json(
            {
              status: 422,
              type: 'urn:etrm:problem:validation-failed',
              title: 'Validation failed',
              detail: 'Clearing account ECC-DEMO-FI already exists',
              violations: [{ field: 'code', message: 'Already used by another account' }],
            },
            { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
          );
        return HttpResponse.json({ ...account, id: 'a2', code: body.code, clearingHouseId: 'ch2' });
      }),
    );
    renderPage(
      <AccountsTab
        entity={entity}
        accounts={[account]}
        loading={false}
        posted={[]}
        selectedId="a1"
        onSelect={onSelect}
      />,
      { url: '/clearing', route: '/clearing', roles: ['BACK_OFFICE'] },
    );

    await userEvent.click(screen.getByTestId('clearing-new-account'));
    const dialog = await screen.findByRole('dialog');
    const submit = within(dialog).getByTestId('clearing-account-submit');
    expect(within(dialog).getByTestId('clearing-account-currency')).toHaveValue('EUR');
    expect(submit).toBeDisabled();
    await fill(within(dialog).getByTestId('clearing-account-code'), 'ecc-demo-fi');
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Clearing house' }));
    // Only clearing houses are offered.
    expect(screen.queryByRole('option', { name: 'Rheinstrom AG' })).toBeNull();
    await userEvent.click(await screen.findByRole('option', { name: 'Nordic CCP' }));
    await fill(within(dialog).getByTestId('clearing-account-member'), 'Nordic Clearing Bank');
    await userEvent.click(submit);

    expect(await within(dialog).findByText('Already used by another account')).toBeInTheDocument();
    await userEvent.clear(within(dialog).getByTestId('clearing-account-code'));
    await fill(within(dialog).getByTestId('clearing-account-code'), 'ncc-demo-fi');
    await userEvent.click(submit);

    await waitFor(() => expect(onSelect).toHaveBeenCalledWith('a2'));
    expect(bodies[1]).toEqual({
      code: 'NCC-DEMO-FI',
      legalEntityId: 'e1',
      clearingHouseId: 'ch2',
      clearingMember: 'Nordic Clearing Bank',
      currency: 'EUR',
    });
  });
});
