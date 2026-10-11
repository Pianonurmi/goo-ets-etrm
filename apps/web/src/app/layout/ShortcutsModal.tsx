import { Group, Kbd, Modal, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';

const SHORTCUTS: { keys: string[]; label: string }[] = [
  { keys: ['Ctrl', 'K'], label: 'shortcuts.palette' },
  { keys: ['/'], label: 'shortcuts.palette' },
  { keys: ['?'], label: 'shortcuts.help' },
  { keys: ['['], label: 'shortcuts.sidebar' },
  { keys: ['Shift', 'D'], label: 'shortcuts.density' },
  { keys: ['Shift', 'L'], label: 'shortcuts.scheme' },
  { keys: ['Esc'], label: 'shortcuts.close' },
];

/** The keyboard shortcuts, opened with `?`. */
export function ShortcutsModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Modal opened={opened} onClose={onClose} title={t('shortcuts.title')} size="md" data-testid="shortcuts">
      <Stack gap="xs">
        {SHORTCUTS.map((shortcut) => (
          <Group key={shortcut.keys.join('+')} justify="space-between">
            <Text size="sm">{t(shortcut.label)}</Text>
            <Group gap={4}>
              {shortcut.keys.map((key) => (
                <Kbd key={key} size="sm">
                  {key}
                </Kbd>
              ))}
            </Group>
          </Group>
        ))}
      </Stack>
    </Modal>
  );
}
