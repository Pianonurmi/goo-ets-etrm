import { Grid, NavLink, Paper, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Navigate, NavLink as RouterLink, useLocation, useParams } from 'react-router';
import { PageHeader } from '@/shared/components/PageHeader';
import { QueryState } from '@/shared/components/QueryState';
import { HelpMarkdown } from './HelpMarkdown';
import { useHelpIndex, useHelpPage } from './pages';
import { HELP_PAGES, isHelpPage, type HelpPageId } from './routes';

function Content({ page }: { page: HelpPageId }) {
  const location = useLocation();
  const source = useHelpPage(page);
  const anchor = location.hash ? decodeURIComponent(location.hash.slice(1)) : undefined;
  return (
    <QueryState isPending={source.isPending} error={source.error}>
      {source.data !== undefined && <HelpMarkdown page={page} source={source.data} anchor={anchor} />}
    </QueryState>
  );
}

/** The whole manual: its pages on the left, the chosen page on the right (`/help/:page#heading`). */
export function HelpPage() {
  const { t } = useTranslation();
  const { page } = useParams();
  const index = useHelpIndex();
  if (!isHelpPage(page)) return <Navigate to="/help/getting-started" replace />;
  const titles = new Map((index.data ?? []).map((entry) => [entry.page, entry.title]));
  return (
    <Stack>
      <PageHeader title={t('help.manual')} description={t('help.manualHint')} />
      <Grid gap="lg">
        <Grid.Col span={{ base: 12, md: 3 }}>
          <Paper withBorder p="xs" component="nav" aria-label={t('help.contents')}>
            <Text size="xs" fw={600} c="dimmed" tt="uppercase" px="sm" py={4}>
              {t('help.contents')}
            </Text>
            {HELP_PAGES.map((id) => (
              <NavLink
                key={id}
                component={RouterLink}
                to={`/help/${id}`}
                label={titles.get(id) ?? id}
                active={id === page}
                variant="light"
                data-testid={`help-page-${id}`}
              />
            ))}
          </Paper>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 9 }}>
          <Paper withBorder p="xl">
            <Content key={page} page={page} />
          </Paper>
        </Grid.Col>
      </Grid>
    </Stack>
  );
}
