import { Badge, Card, Group, SimpleGrid, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useSession } from '@/shared/auth/SessionContext';
import { PageHeader } from '@/shared/components/PageHeader';
import { useEntity } from '@/shared/context/EntityContext';

export function HomePage() {
  const { t } = useTranslation();
  const { me } = useSession();
  const { entity, entities } = useEntity();
  return (
    <Stack>
      <PageHeader title={t('home.welcome', { name: me.displayName })} description={t('home.intro')} />
      <SimpleGrid cols={{ base: 1, md: 3 }}>
        <Card withBorder>
          <Text size="sm" c="dimmed">
            {t('home.yourRoles')}
          </Text>
          <Group gap={6} mt="xs" data-testid="my-roles">
            {me.roles.map((role) => (
              <Badge key={role} variant="light">
                {role}
              </Badge>
            ))}
          </Group>
        </Card>
        <Card withBorder>
          <Text size="sm" c="dimmed">
            {t('home.yourEntities')}
          </Text>
          <Text mt="xs">
            {me.allEntities ? t('home.allEntities') : entities.map((candidate) => candidate.code).join(', ')}
          </Text>
        </Card>
        <Card withBorder>
          <Text size="sm" c="dimmed">
            {t('home.selectedEntity')}
          </Text>
          <Text mt="xs" fw={500}>
            {entity ? `${entity.code} · ${entity.name}` : t('header.noEntity')}
          </Text>
        </Card>
      </SimpleGrid>
    </Stack>
  );
}
