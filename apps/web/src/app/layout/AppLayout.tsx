import {
  ActionIcon,
  AppShell,
  Badge,
  Box,
  Group,
  NavLink,
  ScrollArea,
  Text,
  Tooltip,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import { useDisclosure, useHotkeys } from '@mantine/hooks';
import { IconLayoutSidebarLeftCollapse, IconLayoutSidebarLeftExpand } from '@tabler/icons-react';
import { lazy, Suspense, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, NavLink as RouterLink, useLocation } from 'react-router';
import { useSession } from '@/shared/auth/SessionContext';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { density, setDensity, setSidebarCollapsed, useSidebarCollapsed } from '@/shared/context/preferences';
import { useActionableCounts } from '@/features/inbox/hooks';
import { useActiveTour, welcomed } from '@/shared/tour/store';
import { ProvenaLogo, ProvenaMark } from '../brand/Logo';
import { visibleSections } from '../navigation';
import { CommandPalette } from './CommandPalette';
import { Header } from './Header';
import { ShortcutsModal } from './ShortcutsModal';

const HelpDrawer = lazy(() => import('../help/HelpDrawer'));
const TourHost = lazy(() => import('@/shared/tour/TourHost'));
const WelcomeModal = lazy(() => import('../WelcomeModal'));

function InboxBadge() {
  const { total } = useActionableCounts();
  return total > 0 ? (
    <Badge size="sm" variant="filled" circle={total < 10} data-testid="inbox-badge">
      {total}
    </Badge>
  ) : null;
}

/** An unauthenticated dev server gets a permanent warning strip; the demo says so in the header instead. */
function DevBanner() {
  const { t } = useTranslation();
  const { config } = useSession();
  if (config.authMode !== 'DEV_HEADERS') return null;
  return (
    <Box bg="var(--mantine-color-orange-filled)" c="white" px="md" h={24} data-testid="dev-banner">
      <Text size="xs" fw={500} lh="24px" truncate>
        {t('header.devBanner', { env: config.environment || 'DEV' })}
      </Text>
    </Box>
  );
}

const EXPANDED = 248;
const COLLAPSED = 68;

export function AppLayout() {
  const { t } = useTranslation();
  const { me, config } = useSession();
  const [opened, { toggle, close }] = useDisclosure();
  const [shortcuts, { open: openShortcuts, close: closeShortcuts }] = useDisclosure();
  const [help, { open: openHelp, close: closeHelp }] = useDisclosure();
  const tour = useActiveTour();
  // The showcase greets a first visit; a running tour or a dev server does not need it.
  const [welcome] = useState(() => config.authMode === 'SHOWCASE' && !welcomed());
  const collapsed = useSidebarCollapsed();
  const { setColorScheme } = useMantineColorScheme();
  const scheme = useComputedColorScheme('light');
  const location = useLocation();
  const sections = visibleSections(me.roles);
  const banner = config.authMode === 'DEV_HEADERS' ? 24 : 0;
  useHotkeys([
    ['shift+?', openHelp],
    ['[', () => setSidebarCollapsed(!collapsed)],
    ['shift+D', () => setDensity(density() === 'compact' ? 'comfortable' : 'compact')],
    ['shift+L', () => setColorScheme(scheme === 'dark' ? 'light' : 'dark')],
  ]);
  const matches = (path: string) =>
    path === '/' ? location.pathname === '/' : location.pathname === path || location.pathname.startsWith(path + '/');
  // The most specific matching item is active (/market-data/curves, not also /market-data).
  const activePath = sections
    .flatMap((section) => section.items.map((item) => item.path))
    .filter(matches)
    .sort((a, b) => b.length - a.length)[0];

  return (
    <AppShell
      layout="alt"
      header={{ height: 60 + banner }}
      navbar={{ width: collapsed ? COLLAPSED : EXPANDED, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding={0}
    >
      <AppShell.Header>
        <DevBanner />
        <Box h={60}>
          <Header opened={opened} toggle={toggle} onHelp={openHelp} />
        </Box>
      </AppShell.Header>
      <AppShell.Navbar aria-label={t('nav.main')}>
        <AppShell.Section px={collapsed ? 'xs' : 'md'} h={60} style={{ display: 'flex', alignItems: 'center' }}>
          {collapsed ? <ProvenaMark size={30} title="Provena" /> : <ProvenaLogo />}
        </AppShell.Section>
        <AppShell.Section grow component={ScrollArea} px="xs" py="xs">
          {sections.map((section, index) => (
            <Box key={section.title ?? index} mb="md">
              {section.title && !collapsed && (
                <Text size="xs" fw={600} c="dimmed" tt="uppercase" px="sm" pb={4} style={{ letterSpacing: '0.06em' }}>
                  {t(section.title)}
                </Text>
              )}
              {section.items.map((item) => {
                const link = (
                  <NavLink
                    key={item.path}
                    component={RouterLink}
                    to={item.path}
                    label={collapsed ? undefined : t(item.label)}
                    aria-label={collapsed ? t(item.label) : undefined}
                    leftSection={item.icon}
                    rightSection={!collapsed && item.badge === 'inbox' ? <InboxBadge /> : undefined}
                    active={item.path === activePath}
                    onClick={close}
                    className="etrm-nav-link"
                    variant="light"
                    h={36}
                  />
                );
                return collapsed ? (
                  <Tooltip key={item.path} label={t(item.label)} position="right">
                    {link}
                  </Tooltip>
                ) : (
                  link
                );
              })}
            </Box>
          ))}
        </AppShell.Section>
        <AppShell.Section p="xs" visibleFrom="sm">
          <Group justify={collapsed ? 'center' : 'flex-end'}>
            <Tooltip label={t(collapsed ? 'nav.expand' : 'nav.collapse')} position="right">
              <ActionIcon
                variant="subtle"
                color="gray"
                onClick={() => setSidebarCollapsed(!collapsed)}
                aria-label={t(collapsed ? 'nav.expand' : 'nav.collapse')}
                data-testid="sidebar-toggle"
              >
                {collapsed ? <IconLayoutSidebarLeftExpand size={18} /> : <IconLayoutSidebarLeftCollapse size={18} />}
              </ActionIcon>
            </Tooltip>
          </Group>
        </AppShell.Section>
      </AppShell.Navbar>
      <AppShell.Main className="etrm-canvas">
        <Box px={{ base: 'md', md: 'xl' }} py="lg" maw={1440} mx="auto">
          <Suspense fallback={<TableSkeleton />}>
            <Outlet />
          </Suspense>
        </Box>
      </AppShell.Main>
      <CommandPalette />
      <ShortcutsModal opened={shortcuts} onClose={closeShortcuts} />
      {tour && (
        <Suspense fallback={null}>
          <TourHost active={tour} />
        </Suspense>
      )}
      {welcome && !tour && (
        <Suspense fallback={null}>
          <WelcomeModal />
        </Suspense>
      )}
      {help && (
        <Suspense fallback={null}>
          <HelpDrawer opened={help} onClose={closeHelp} onShortcuts={openShortcuts} />
        </Suspense>
      )}
    </AppShell>
  );
}
