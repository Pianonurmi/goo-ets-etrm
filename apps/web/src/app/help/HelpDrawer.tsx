import { Button, Drawer, Group, Stack, Text } from '@mantine/core';
import { IconBook2, IconKeyboard, IconRoute } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router';
import { QueryState } from '@/shared/components/QueryState';
import { HelpMarkdown } from './HelpMarkdown';
import { useHelpIndex, useHelpPage } from './pages';
import { startTour } from '@/shared/tour/store';
import { tourForPath } from '@/shared/tour/tours';
import { helpFor } from './routes';

/** Help for the screen in view, from the manual (`docs/user-guide`); opened with the `?` button or Shift ?. */
export default function HelpDrawer({
  opened,
  onClose,
  onShortcuts,
}: {
  opened: boolean;
  onClose: () => void;
  onShortcuts: () => void;
}) {
  const { t } = useTranslation();
  const location = useLocation();
  const target = helpFor(location.pathname);
  const page = useHelpPage(target.page);
  const index = useHelpIndex();
  const title = (index.data ?? []).find((entry) => entry.page === target.page)?.title ?? t('help.title');
  const tour = tourForPath(location.pathname);
  const manualLink = `/help/${target.page}${target.anchor ? `#${target.anchor}` : ''}`;
  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size="lg"
      title={
        <Stack gap={0}>
          <Text size="xs" c="dimmed" fw={500}>
            {t('help.title')}
          </Text>
          <Text fw={600}>{title}</Text>
        </Stack>
      }
      data-testid="help-drawer"
    >
      <Stack gap="lg">
        <Group gap="xs">
          <Button
            component={Link}
            to={manualLink}
            onClick={onClose}
            size="xs"
            variant="light"
            leftSection={<IconBook2 size={14} />}
            data-testid="help-open-manual"
          >
            {t('help.openManual')}
          </Button>
          {tour && (
            <Button
              size="xs"
              variant="light"
              leftSection={<IconRoute size={14} />}
              onClick={() => {
                onClose();
                startTour(tour.id);
              }}
              data-testid="help-take-tour"
            >
              {t('help.takeTour')}
            </Button>
          )}
          <Button
            size="xs"
            variant="default"
            leftSection={<IconKeyboard size={14} />}
            onClick={() => {
              onClose();
              onShortcuts();
            }}
          >
            {t('help.shortcuts')}
          </Button>
        </Group>
        <QueryState isPending={page.isPending} error={page.error}>
          {page.data !== undefined && <HelpMarkdown page={target.page} source={page.data} anchor={target.anchor} />}
        </QueryState>
      </Stack>
    </Drawer>
  );
}
