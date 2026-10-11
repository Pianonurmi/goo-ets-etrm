import '@mantine/core/styles.layer.css';
import '@mantine/dates/styles.layer.css';
import '@mantine/notifications/styles.layer.css';
import '@mantine/spotlight/styles.layer.css';
import '@mantine/charts/styles.layer.css';
import './app/i18n';
import { Alert, Button, Center, Loader, MantineProvider, Stack } from '@mantine/core';
import { ModalsProvider } from '@mantine/modals';
import { Notifications } from '@mantine/notifications';
import { lazy, StrictMode, Suspense, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { useTranslation } from 'react-i18next';
import { App } from './app/App';
import { PreparingDemo } from './app/pages/PreparingDemo';
import { cssVariablesResolver, theme } from './app/theme';
import { startSession, type UiConfig } from './shared/auth/session';

const LoginPage = lazy(() => import('./app/pages/LoginPage'));

type BootState =
  | { kind: 'starting' }
  | { kind: 'login'; config: UiConfig }
  | { kind: 'seeding'; config: UiConfig }
  | { kind: 'ready'; config: UiConfig }
  | { kind: 'failed'; message: string };

function Loading() {
  const { t } = useTranslation();
  return (
    <Center h="100vh">
      <Loader aria-label={t('app.loading')} />
    </Center>
  );
}

/** Reads the UI configuration and signs in (or picks the dev persona) before the application renders. */
function Boot() {
  const { t } = useTranslation();
  const [state, setState] = useState<BootState>({ kind: 'starting' });
  useEffect(() => {
    startSession()
      .then(({ config, ready, needsLogin }) => {
        if (config.demoSeed?.state === 'RUNNING') setState({ kind: 'seeding', config });
        else if (needsLogin) setState({ kind: 'login', config });
        else if (ready) setState({ kind: 'ready', config });
      })
      .catch((error: unknown) => {
        setState({ kind: 'failed', message: error instanceof Error ? error.message : String(error) });
      });
  }, []);
  if (state.kind === 'ready') return <App config={state.config} />;
  if (state.kind === 'seeding') return <PreparingDemo config={state.config} />;
  if (state.kind === 'login') {
    const { config } = state;
    return (
      <Suspense fallback={<Loading />}>
        <LoginPage onSignedIn={() => setState({ kind: 'ready', config })} />
      </Suspense>
    );
  }
  if (state.kind === 'failed') {
    return (
      <Center h="100vh">
        <Stack maw={520}>
          <Alert color="red" title={t('app.startFailed')}>
            {state.message}
          </Alert>
          <Button variant="light" onClick={() => window.location.reload()}>
            {t('app.retry')}
          </Button>
        </Stack>
      </Center>
    );
  }
  return <Loading />;
}

const root = document.getElementById('root');
if (!root) throw new Error('index.html has no #root');

createRoot(root).render(
  <StrictMode>
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} defaultColorScheme="auto">
      <Notifications position="top-right" />
      <ModalsProvider>
        <Boot />
      </ModalsProvider>
    </MantineProvider>
  </StrictMode>,
);
