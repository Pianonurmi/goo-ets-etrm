import { MantineProvider } from '@mantine/core';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useState } from 'react';
import { ORIGIN, server } from '@/test/server';
import { CriteriaBuilder } from './CriteriaBuilder';
import { EXAMPLE, type Criterion } from './model/tree';

const catalogue = {
  fields: [
    {
      attribute: 'DEVICE_COUNTRY',
      kind: 'STRING',
      label: 'Device country',
      operators: ['EQUALS', 'IN'],
      valueSource: 'COUNTRY',
      values: [],
    },
    {
      attribute: 'ENERGY_SOURCE',
      kind: 'TAXONOMY',
      label: 'Energy source',
      operators: ['DESCENDANT_OF'],
      valueSource: 'TAXONOMY',
      taxonomyScheme: 'ENERGY_SOURCE',
      values: [],
    },
  ],
  regionScheme: 'REGION',
  labelScheme: 'LABEL',
  vintageRules: [],
};

let lastValidated: unknown = null;

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  server.use(
    http.get(`${ORIGIN}/api/v1/products/criteria/catalogue`, () => HttpResponse.json(catalogue)),
    http.get(`${ORIGIN}/api/v1/taxonomy/:scheme`, () => HttpResponse.json([])),
    http.post(`${ORIGIN}/api/v1/products/criteria/validate`, async ({ request }) => {
      lastValidated = await request.json();
      return HttpResponse.json({ json: '{}', summary: 'Nordic hydro', specificity: 3.5 });
    }),
  );
});

function Harness({ onValue }: { onValue: (value: Criterion) => void }) {
  const [value, setValue] = useState<Criterion>(EXAMPLE);
  return (
    <CriteriaBuilder
      value={value}
      onChange={(next) => {
        setValue(next);
        onValue(next);
      }}
    />
  );
}

function renderBuilder(onValue: (value: Criterion) => void = () => undefined) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MantineProvider>
      <QueryClientProvider client={client}>
        <Harness onValue={onValue} />
      </QueryClientProvider>
    </MantineProvider>,
  );
}

describe('CriteriaBuilder', () => {
  it('shows the server summary of a complete criterion', async () => {
    renderBuilder();
    expect(await screen.findByText('Nordic hydro')).toBeInTheDocument();
    expect(screen.getByText('Specificity 3.5')).toBeInTheDocument();
    expect(lastValidated).toEqual(EXAMPLE);
  });

  it('adds a condition from the catalogue and asks for its value before validating', async () => {
    const user = userEvent.setup();
    let latest: Criterion = EXAMPLE;
    renderBuilder((value) => (latest = value));
    await screen.findByText('Nordic hydro');
    await user.click(screen.getAllByTestId('add-condition')[0] as HTMLElement);
    await user.click(await screen.findByText('Device country · one of'));
    expect(latest).toEqual({
      ...EXAMPLE,
      of: [...(EXAMPLE.type === 'all' ? EXAMPLE.of : []), { type: 'in', attribute: 'DEVICE_COUNTRY', values: [] }],
    });
    await waitFor(() =>
      expect(screen.getByTestId('criteria-incomplete')).toHaveTextContent('1 condition still needs a value'),
    );
    expect(screen.getAllByTestId('condition')).toHaveLength(3);
  });

  it('switches a group between all and any', async () => {
    const user = userEvent.setup();
    let latest: Criterion = EXAMPLE;
    renderBuilder((value) => (latest = value));
    await user.click(await screen.findByText('Any of'));
    expect(latest.type).toBe('any');
  });
});
