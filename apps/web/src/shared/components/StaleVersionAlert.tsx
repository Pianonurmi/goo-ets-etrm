import { Alert, Button, Group, Stack, Text } from '@mantine/core';
import { IconRefreshAlert } from '@tabler/icons-react';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { fieldPathLabel, type FieldChange } from '@/shared/forms/staleVersion';
import { statusLabel } from './StatusBadge';

/** A `label` for the alert from a map of field path to catalogue key; other fields are spelled out from the path. */
export function labelsFrom(
  keys: Record<string, string>,
  t: (key: string) => string,
): (path: string) => string | undefined {
  return (path) => {
    const key = keys[path];
    return key ? t(key) : undefined;
  };
}

function shown(value: unknown, yes: string, no: string): string {
  if (value === null || value === undefined || value === '') return '—';
  if (typeof value === 'boolean') return value ? yes : no;
  if (typeof value === 'string') return /^[A-Z][A-Z0-9_]+$/.test(value) ? statusLabel(value) : value;
  if (typeof value === 'number' || typeof value === 'bigint') return value.toString();
  return JSON.stringify(value);
}

/**
 * Shown on a form whose save was refused because the record changed: what changed on the server, and the way
 * back (W5). `label` names a field the way the form does; the default spells it out from its path.
 */
export function StaleVersionAlert({
  changes,
  label,
  onKeepEdits,
  onDiscardEdits,
}: {
  changes: FieldChange[];
  label?: (path: string) => string | undefined;
  onKeepEdits: () => void;
  onDiscardEdits: () => void;
}) {
  const { t } = useTranslation();
  const ref = useRef<HTMLDivElement>(null);
  // The save button may be far below; bring the explanation into view.
  useEffect(() => {
    ref.current?.scrollIntoView?.({ block: 'nearest' });
  }, []);
  const text = (value: unknown) => shown(value, t('common.yes'), t('common.no'));
  return (
    <Alert
      ref={ref}
      color="yellow"
      icon={<IconRefreshAlert size={18} />}
      title={t('conflict.title')}
      data-testid="stale-version-alert"
    >
      <Stack gap="xs">
        <Text size="sm">{changes.length > 0 ? t('conflict.lead') : t('conflict.noVisibleChange')}</Text>
        {changes.length > 0 && (
          <Stack gap={4} component="ul" m={0} pl="md" data-testid="stale-version-changes">
            {changes.map((change) => (
              <li key={change.path}>
                <Text size="sm" component="span" fw={600}>
                  {label?.(change.path) ?? fieldPathLabel(change.path)}
                </Text>
                <Text size="sm" component="span">
                  {': '}
                  {t('conflict.change', { was: text(change.loaded), now: text(change.current) })}
                  {change.edited && ` ${t('conflict.yourEdit', { mine: text(change.mine) })}`}
                </Text>
              </li>
            ))}
          </Stack>
        )}
        <Group gap="xs">
          <Button size="xs" onClick={onKeepEdits} data-testid="stale-keep-edits">
            {t('conflict.keepEdits')}
          </Button>
          <Button size="xs" variant="default" onClick={onDiscardEdits} data-testid="stale-discard-edits">
            {t('conflict.discardEdits')}
          </Button>
        </Group>
      </Stack>
    </Alert>
  );
}
