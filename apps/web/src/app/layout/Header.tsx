import {
  ActionIcon,
  Badge,
  Box,
  Burger,
  Group,
  Menu,
  Select,
  Text,
  Title,
  Tooltip,
  UnstyledButton,
  useComputedColorScheme,
  useMantineColorScheme,
} from '@mantine/core';
import { IconChevronDown, IconLogout, IconMoon, IconSun, IconUserCircle } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { signOut } from '@/shared/auth/oidc';
import { choosePersona, currentPersona, PERSONAS } from '@/shared/auth/personas';
import { useSession } from '@/shared/auth/SessionContext';
import { useEntity } from '@/shared/context/EntityContext';

function ColorSchemeToggle() {
  const { t } = useTranslation();
  const { setColorScheme } = useMantineColorScheme();
  const computed = useComputedColorScheme('light');
  return (
    <Tooltip label={t('header.toggleColorScheme')}>
      <ActionIcon
        variant="default"
        size="lg"
        aria-label={t('header.toggleColorScheme')}
        onClick={() => setColorScheme(computed === 'dark' ? 'light' : 'dark')}
      >
        {computed === 'dark' ? <IconSun size={18} /> : <IconMoon size={18} />}
      </ActionIcon>
    </Tooltip>
  );
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
      w={280}
      visibleFrom="sm"
      data-testid="entity-select"
    />
  );
}

/** Development only: act as a persona; the API reads the X-Actor / X-Roles headers (ADR 0026). */
function PersonaMenu() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const persona = currentPersona();
  return (
    <Menu position="bottom-end" width={280}>
      <Menu.Target>
        <UnstyledButton aria-label={t('header.actAs')} data-testid="persona-menu">
          <Group gap={6} wrap="nowrap">
            <IconUserCircle size={22} />
            <Box visibleFrom="sm">
              <Text size="sm" fw={500} lh={1.1}>
                {persona.name}
              </Text>
              <Text size="xs" c="dimmed" lh={1.1}>
                {persona.roles.join(', ')}
              </Text>
            </Box>
            <IconChevronDown size={14} />
          </Group>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{t('header.actAs')}</Menu.Label>
        {PERSONAS.map((candidate) => (
          <Menu.Item
            key={candidate.id}
            data-testid={`persona-${candidate.id}`}
            onClick={() => {
              choosePersona(candidate.id);
              void queryClient.resetQueries();
            }}
            rightSection={
              <Text size="xs" c="dimmed">
                {candidate.roles.join(', ')}
              </Text>
            }
            fw={candidate.id === persona.id ? 700 : undefined}
          >
            {candidate.name}
          </Menu.Item>
        ))}
      </Menu.Dropdown>
    </Menu>
  );
}

function UserMenu() {
  const { t } = useTranslation();
  const { me } = useSession();
  return (
    <Menu position="bottom-end" width={240}>
      <Menu.Target>
        <UnstyledButton aria-label={me.displayName}>
          <Group gap={6} wrap="nowrap">
            <IconUserCircle size={22} />
            <Text size="sm" fw={500} visibleFrom="sm">
              {me.displayName}
            </Text>
            <IconChevronDown size={14} />
          </Group>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        <Menu.Label>{t('header.roles')}</Menu.Label>
        <Box px="sm" pb="xs">
          <Text size="xs">{me.roles.join(', ')}</Text>
        </Box>
        <Menu.Divider />
        <Menu.Item leftSection={<IconLogout size={16} />} onClick={() => void signOut()}>
          {t('header.signOut')}
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

export function Header({ opened, toggle }: { opened: boolean; toggle: () => void }) {
  const { t } = useTranslation();
  const { config } = useSession();
  const dev = config.authMode === 'DEV_HEADERS';
  return (
    <Group h="100%" px="md" justify="space-between" wrap="nowrap">
      <Group gap="sm" wrap="nowrap">
        <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label={t('header.toggleNavigation')} />
        <Title order={4}>{t('app.title')}</Title>
        <Text size="sm" c="dimmed" visibleFrom="md">
          {t('app.subtitle')}
        </Text>
        {config.environment && (
          <Badge color={dev ? 'orange' : 'gray'} variant="light">
            {config.environment}
          </Badge>
        )}
      </Group>
      <Group gap="sm" wrap="nowrap">
        <EntitySelect />
        <ColorSchemeToggle />
        {dev ? <PersonaMenu /> : <UserMenu />}
      </Group>
    </Group>
  );
}
