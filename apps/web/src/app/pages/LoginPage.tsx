import { Alert, Box, Button, Center, List, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { asProblemError } from '@/shared/api/problem';
import { signInShowcase, takeSessionExpired } from '@/shared/auth/showcase';

interface Credentials {
  username: string;
  password: string;
}

/** The showcase sign-in (ADR 0032): one demo account, then the visitor picks a persona inside the application. */
export function LoginPage({ onSignedIn }: { onSignedIn: () => void }) {
  const { t } = useTranslation();
  const [expired] = useState(takeSessionExpired);
  const [failure, setFailure] = useState<{ status: number; message: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const form = useForm<Credentials>({
    initialValues: { username: '', password: '' },
    validate: {
      username: (value) => (value.trim() ? null : t('login.required')),
      password: (value) => (value ? null : t('login.required')),
    },
  });

  const submit = async ({ username, password }: Credentials) => {
    setBusy(true);
    setFailure(null);
    try {
      await signInShowcase(username.trim(), password);
      onSignedIn();
    } catch (error) {
      const problem = asProblemError(error);
      setFailure({ status: problem.status, message: problem.message });
      form.setFieldValue('password', '');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Center mih="100vh" p="md" bg="var(--mantine-color-body)">
      <Stack w="100%" maw={440} gap="lg">
        <Box ta="center">
          <Title order={1} size="h2">
            {t('app.title')}
          </Title>
          <Text c="dimmed">{t('login.tagline')}</Text>
        </Box>
        <Paper withBorder radius="md" p="xl" component="main">
          <form onSubmit={form.onSubmit((values) => void submit(values))} noValidate>
            <Stack>
              <Title order={2} size="h4">
                {t('login.title')}
              </Title>
              {expired && (
                <Alert color="blue" data-testid="session-expired">
                  {t('login.expired')}
                </Alert>
              )}
              {failure && (
                <Alert
                  color="red"
                  title={failure.status === 429 ? t('login.throttled') : t('login.failed')}
                  data-testid="login-error"
                >
                  {failure.message}
                </Alert>
              )}
              <TextInput
                label={t('login.username')}
                autoComplete="username"
                data-testid="login-username"
                {...form.getInputProps('username')}
              />
              <PasswordInput
                label={t('login.password')}
                autoComplete="current-password"
                data-testid="login-password"
                {...form.getInputProps('password')}
              />
              <Button type="submit" loading={busy} fullWidth data-testid="login-submit">
                {t('login.submit')}
              </Button>
            </Stack>
          </form>
        </Paper>
        <Paper withBorder radius="md" p="lg" component="section" aria-labelledby="login-about">
          <Title order={2} size="h5" id="login-about" mb="xs">
            {t('login.aboutTitle')}
          </Title>
          <List size="sm" spacing={6}>
            <List.Item>{t('login.aboutPersonas')}</List.Item>
            <List.Item>{t('login.aboutShared')}</List.Item>
            <List.Item>{t('login.aboutReset')}</List.Item>
            <List.Item>{t('login.aboutFictitious')}</List.Item>
          </List>
          <Text size="xs" c="dimmed" mt="sm">
            {t('login.access')}
          </Text>
        </Paper>
      </Stack>
    </Center>
  );
}

export default LoginPage;
