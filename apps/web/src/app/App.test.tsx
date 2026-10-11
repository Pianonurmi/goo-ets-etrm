import { MantineProvider } from '@mantine/core';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { configureApiAuth } from '@/shared/api/client';
import { currentPersona, personaHeaders } from '@/shared/auth/personas';
import { server } from '@/test/server';
import { App } from './App';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

beforeEach(() => {
  window.localStorage.clear();
  configureApiAuth(
    (request) => {
      Object.entries(personaHeaders(currentPersona())).forEach(([name, value]) => request.headers.set(name, value));
    },
    () => undefined,
  );
});

function renderApp() {
  return render(
    <MantineProvider>
      <App config={{ authMode: 'DEV_HEADERS', environment: 'TEST', version: 'dev', demo: false }} />
    </MantineProvider>,
  );
}

describe('App shell', () => {
  it('signs in as the default persona and shows the dev banner, roles and entity', async () => {
    renderApp();
    expect(
      await screen.findByText(/^Good (morning|afternoon|evening), Tia$/, {}, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(screen.getByTestId('dev-banner')).toHaveTextContent('TEST environment');
    expect(within(screen.getByTestId('my-roles')).getByText('Trader')).toBeInTheDocument();
    expect(await screen.findByTestId('kpi-tasks')).toHaveTextContent('2');
    expect((await screen.findAllByText('DEMO-FI · Demo Energia Oy')).length).toBeGreaterThan(0);
  });

  it('switches persona and reloads the session', async () => {
    const user = userEvent.setup();
    renderApp();
    await screen.findByText(/^Good (morning|afternoon|evening), Tia$/, {}, { timeout: 5000 });
    await user.click(screen.getByTestId('persona-menu'));
    await user.click(await screen.findByTestId('persona-middle2'));
    expect(
      await screen.findByText(/^Good (morning|afternoon|evening), Max$/, {}, { timeout: 5000 }),
    ).toBeInTheDocument();
    expect(within(screen.getByTestId('my-roles')).getByText('Middle office')).toBeInTheDocument();
  });
});
