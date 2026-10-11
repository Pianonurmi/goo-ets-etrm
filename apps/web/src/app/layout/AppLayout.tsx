import { AppShell, Badge, Box, NavLink, ScrollArea, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { Suspense } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, NavLink as RouterLink, useLocation } from 'react-router';
import { useSession } from '@/shared/auth/SessionContext';
import { QueryState } from '@/shared/components/QueryState';
import { useActionableCounts } from '@/features/inbox/hooks';
import { visibleSections } from '../navigation';
import { CommandPalette } from './CommandPalette';
import { Header } from './Header';

function InboxBadge() {
  const { total } = useActionableCounts();
  return total > 0 ? (
    <Badge size="sm" color="red" variant="filled" circle={total < 10} data-testid="inbox-badge">
      {total}
    </Badge>
  ) : null;
}

/** The strip above the header: an unauthenticated dev server, or the shared public demo. */
function EnvironmentBanner() {
  const { t } = useTranslation();
  const { config } = useSession();
  if (config.authMode === 'SHOWCASE') {
    return (
      <Box bg="var(--mantine-color-teal-filled)" c="white" px="md" h={24} data-testid="demo-banner">
        <Text size="xs" fw={500} lh="24px" truncate>
          {t('header.demoBanner')}
        </Text>
      </Box>
    );
  }
  if (config.authMode !== 'DEV_HEADERS') return null;
  return (
    <Box bg="var(--mantine-color-orange-filled)" c="white" px="md" h={24} data-testid="dev-banner">
      <Text size="xs" fw={500} lh="24px" truncate>
        {t('header.devBanner', { env: config.environment || 'DEV' })}
      </Text>
    </Box>
  );
}

export function AppLayout() {
  const { t } = useTranslation();
  const { me, config } = useSession();
  const [opened, { toggle, close }] = useDisclosure();
  const location = useLocation();
  const sections = visibleSections(me.roles);
  const banner = config.authMode === 'OIDC' ? 0 : 24;
  const matches = (path: string) =>
    path === '/' ? location.pathname === '/' : location.pathname === path || location.pathname.startsWith(path + '/');
  // The most specific matching item is active (/market-data/curves, not also /market-data).
  const activePath = sections
    .flatMap((section) => section.items.map((item) => item.path))
    .filter(matches)
    .sort((a, b) => b.length - a.length)[0];
  const isActive = (path: string) => path === activePath;

  return (
    <AppShell
      header={{ height: 56 + banner }}
      navbar={{ width: 240, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <EnvironmentBanner />
        <Box h={56}>
          <Header opened={opened} toggle={toggle} />
        </Box>
      </AppShell.Header>
      <AppShell.Navbar p="xs" aria-label={t('nav.home')}>
        <ScrollArea>
          {sections.map((section, index) => (
            <Box key={section.title ?? index} mb="sm">
              {section.title && (
                <Text size="xs" fw={700} c="dimmed" tt="uppercase" px="sm" pt="xs" pb={4}>
                  {t(section.title)}
                </Text>
              )}
              {section.items.map((item) => (
                <NavLink
                  key={item.path}
                  component={RouterLink}
                  to={item.path}
                  label={t(item.label)}
                  leftSection={item.icon}
                  rightSection={item.badge === 'inbox' ? <InboxBadge /> : undefined}
                  active={isActive(item.path)}
                  onClick={close}
                />
              ))}
            </Box>
          ))}
        </ScrollArea>
      </AppShell.Navbar>
      <AppShell.Main>
        <Suspense
          fallback={
            <QueryState isPending error={null}>
              {null}
            </QueryState>
          }
        >
          <Outlet />
        </Suspense>
      </AppShell.Main>
      <CommandPalette />
    </AppShell>
  );
}
