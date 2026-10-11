import {
  ActionIcon,
  Avatar,
  Badge,
  Box,
  Burger,
  Group,
  Indicator,
  Menu,
  SegmentedControl,
  Select,
  Stack,
  Text,
  Tooltip,
  UnstyledButton,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import {
  IconAdjustmentsHorizontal,
  IconBell,
  IconChevronDown,
  IconHelp,
  IconId,
  IconLogout,
  IconMoon,
  IconSearch,
  IconSun,
} from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { spotlight } from '@mantine/spotlight';
import type { Role } from '@/shared/api/types';
import { signOut } from '@/shared/auth/oidc';
import { choosePersona, currentPersona, personas } from '@/shared/auth/personas';
import { useSession } from '@/shared/auth/SessionContext';
import { signOutShowcase } from '@/shared/auth/showcase';
import { useEntity } from '@/shared/context/EntityContext';
import { setDensity, useDensity } from '@/shared/context/preferences';
import { useActionableCounts } from '@/features/inbox/hooks';

function initials(name: string): string {
  return name
    .split(/\s+/)
    .map((part) => part.charAt(0))
    .join('')
    .slice(0, 2)
    .toUpperCase();
}

function EntitySelect() {
  const { t } = useTranslation();
  const { entities, entity, select } = useEntity();
  return (
    <Select
      aria-label={t('header.entity')}
      placeholder={t('header.noEntity')}
      data={entities.map((candidate) => ({
        value: candidate.id,
        label: `${candidate.code} · ${candidate.name}`,
      }))}
      value={entity?.id ?? null}
      onChange={(id) => {
        if (id) select(id);
      }}
      allowDeselect={false}
      searchable
      w={260}
      visibleFrom="sm"
      data-testid="entity-select"
    />
  );
}

/** Opens the command palette; looks like a search field, as in the design. */
function SearchButton() {
  const { t } = useTranslation();
  return (
    <>
      <UnstyledButton
        onClick={() => spotlight.open()}
        aria-label={t('palette.open')}
        data-testid="palette-button"
        visibleFrom="md"
        className="etrm-search-button"
      >
        <Group gap={8} wrap="nowrap" justify="space-between" w="100%">
          <Group gap={8} wrap="nowrap">
            <IconSearch size={16} />
            <Text size="sm" c="dimmed" truncate>
              {t('palette.placeholderShort')}
            </Text>
          </Group>
          <span className="etrm-kbd">Ctrl K</span>
        </Group>
      </UnstyledButton>
      <ActionIcon
        variant="default"
        size="lg"
        hiddenFrom="md"
        onClick={() => spotlight.open()}
        aria-label={t('palette.open')}
      >
        <IconSearch size={18} />
      </ActionIcon>
    </>
  );
}

function EnvironmentBadge() {
  const { t } = useTranslation();
  const { config } = useSession();
  if (config.authMode === 'SHOWCASE') {
    return (
      <Tooltip label={t('header.demoBanner')} multiline w={300}>
        <Badge variant="light" tt="none" radius="xl" data-testid="demo-banner" visibleFrom="sm">
          {t('header.demoBadge')}
        </Badge>
      </Tooltip>
    );
  }
  if (!config.environment) return null;
  return (
    <Badge color={config.authMode === 'DEV_HEADERS' ? 'orange' : 'gray'} variant="light" radius="xl" visibleFrom="sm">
      {config.environment}
    </Badge>
  );
}

function InboxButton() {
  const { t } = useTranslation();
  const { total } = useActionableCounts();
  return (
    <Tooltip label={t('header.inbox', { count: total })}>
      <Indicator label={total} size={16} disabled={total === 0} offset={4} color="red" inline>
        <ActionIcon
          component={Link}
          to="/inbox"
          variant="default"
          size="lg"
          aria-label={t('header.inbox', { count: total })}
        >
          <IconBell size={18} />
        </ActionIcon>
      </Indicator>
    </Tooltip>
  );
}

function HelpButton({ onOpen }: { onOpen: () => void }) {
  const { t } = useTranslation();
  return (
    <Tooltip label={t('header.help')}>
      <ActionIcon variant="default" size="lg" onClick={onOpen} aria-label={t('header.help')} data-testid="help-button">
        <IconHelp size={18} />
      </ActionIcon>
    </Tooltip>
  );
}

/** Colour scheme and density. */
function DisplayMenu() {
  const { t } = useTranslation();
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('light');
  const density = useDensity();
  return (
    <Menu position="bottom-end" width={240} closeOnItemClick={false}>
      <Menu.Target>
        <ActionIcon variant="default" size="lg" aria-label={t('header.display')} data-testid="display-menu">
          <IconAdjustmentsHorizontal size={18} />
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{t('header.display')}</Menu.Label>
        <Menu.Item
          leftSection={computed === 'dark' ? <IconSun size={16} /> : <IconMoon size={16} />}
          onClick={() => setColorScheme(computed === 'dark' ? 'light' : 'dark')}
          data-testid="toggle-color-scheme"
        >
          {t('header.toggleColorScheme')}
        </Menu.Item>
        <Box px="sm" py={6}>
          <Text size="xs" c="dimmed" mb={4}>
            {t('header.density')}
          </Text>
          <SegmentedControl
            size="xs"
            fullWidth
            value={density}
            onChange={(value) => setDensity(value)}
            data={[
              { value: 'comfortable', label: t('header.comfortable') },
              { value: 'compact', label: t('header.compact') },
            ]}
            data-testid="density"
          />
        </Box>
      </Menu.Dropdown>
    </Menu>
  );
}

function RoleLine({ roles }: { roles: readonly Role[] }) {
  const { t } = useTranslation();
  return (
    <Text size="xs" c="dimmed" lh={1.2} truncate>
      {roles.map((role) => t(`roles.${role}`)).join(', ')}
    </Text>
  );
}

/**
 * Act as a persona: in development the API reads the X-Actor / X-Roles headers (ADR 0026); in the showcase it reads
 * X-Persona and takes the roles from its own catalogue (ADR 0032).
 */
function PersonaMenu() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const persona = currentPersona();
  return (
    <Menu position="bottom-end" width={300}>
      <Menu.Target>
        <UnstyledButton aria-label={t('header.actAs')} data-testid="persona-menu" className="etrm-persona-button">
          <Group gap={10} wrap="nowrap">
            <Avatar size={30} radius="xl" color="brand" variant="filled">
              {initials(persona.name)}
            </Avatar>
            <Box visibleFrom="sm" maw={160}>
              <Text size="sm" fw={500} lh={1.2} truncate>
                {persona.name}
              </Text>
              <RoleLine roles={persona.roles} />
            </Box>
            <IconChevronDown size={14} />
          </Group>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{t('header.actAs')}</Menu.Label>
        {personas().map((candidate) => (
          <Menu.Item
            key={candidate.id}
            data-testid={`persona-${candidate.id}`}
            onClick={() => {
              choosePersona(candidate.id);
              void queryClient.resetQueries();
            }}
            leftSection={
              <Avatar size={24} radius="xl" color={candidate.id === persona.id ? 'brand' : 'gray'} variant="light">
                {initials(candidate.name)}
              </Avatar>
            }
            fw={candidate.id === persona.id ? 600 : undefined}
          >
            <Stack gap={0}>
              <Text size="sm" fw="inherit">
                {candidate.name}
              </Text>
              <RoleLine roles={candidate.roles} />
            </Stack>
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

/** Showcase: who signed in, and sign out. The persona is chosen separately. */
function ShowcaseUserMenu() {
  const { t } = useTranslation();
  const { me } = useSession();
  return (
    <Menu position="bottom-end" width={240}>
      <Menu.Target>
        <ActionIcon variant="default" size="lg" aria-label={t('header.account')} data-testid="account-menu">
          <IconId size={18} />
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{t('header.signedInAs')}</Menu.Label>
        <Box px="sm" pb="xs">
          <Text size="sm" fw={500} data-testid="account-login">
            {me.login}
          </Text>
        </Box>
        <Menu.Divider />
        <Menu.Item leftSection={<IconLogout size={16} />} onClick={() => void signOutShowcase()} data-testid="sign-out">
          {t('header.signOut')}
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

function UserMenu() {
  const { t } = useTranslation();
  const { me } = useSession();
  return (
    <Menu position="bottom-end" width={260}>
      <Menu.Target>
        <UnstyledButton aria-label={me.displayName} className="etrm-persona-button">
          <Group gap={10} wrap="nowrap">
            <Avatar size={30} radius="xl" color="brand" variant="filled">
              {initials(me.displayName)}
            </Avatar>
            <Box visibleFrom="sm" maw={160}>
              <Text size="sm" fw={500} lh={1.2} truncate>
                {me.displayName}
              </Text>
              <RoleLine roles={me.roles} />
            </Box>
            <IconChevronDown size={14} />
          </Group>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Item leftSection={<IconLogout size={16} />} onClick={() => void signOut()}>
          {t('header.signOut')}
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

export function Header({ opened, toggle, onHelp }: { opened: boolean; toggle: () => void; onHelp: () => void }) {
  const { t } = useTranslation();
  const { config } = useSession();
  const personasOn = config.authMode === 'DEV_HEADERS' || config.authMode === 'SHOWCASE';
  return (
    <Group h="100%" px="md" justify="space-between" wrap="nowrap" gap="sm">
      <Group gap="sm" wrap="nowrap" style={{ flex: 1, minWidth: 0 }}>
        <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label={t('header.toggleNavigation')} />
        <EntitySelect />
        <SearchButton />
      </Group>
      <Group gap="xs" wrap="nowrap">
        <EnvironmentBadge />
        <InboxButton />
        <HelpButton onOpen={onHelp} />
        <DisplayMenu />
        {personasOn ? <PersonaMenu /> : <UserMenu />}
        {config.authMode === 'SHOWCASE' && <ShowcaseUserMenu />}
      </Group>
    </Group>
  );
}
