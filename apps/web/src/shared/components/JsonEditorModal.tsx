import { Button, Group, JsonInput, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ProblemAlert } from './ProblemAlert';

/** Parses the editor text; a JSON object or nothing (the API checks the content itself). */
export function parseJsonObject(text: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(text);
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/**
 * Edits a JSON definition (import and statement formats): an optional code for a new entry, the JSON with
 * formatting and a syntax check, and the server's problem shown in place.
 */
export function JsonEditorModal({
  title,
  hint,
  initial,
  code,
  onCodeChange,
  codeLabel,
  pending,
  error,
  onSubmit,
  onClose,
}: {
  title: string;
  hint?: string;
  initial: unknown;
  /** When given, a code field is shown (new entries); its value is passed back with the JSON. */
  code?: string;
  onCodeChange?: (code: string) => void;
  codeLabel?: string;
  pending: boolean;
  error: unknown;
  onSubmit: (value: Record<string, unknown>) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [text, setText] = useState(() => JSON.stringify(initial ?? {}, null, 2));
  const parsed = parseJsonObject(text);
  return (
    <Modal opened onClose={onClose} title={title} size="xl">
      <Stack>
        {hint && (
          <Text size="sm" c="dimmed">
            {hint}
          </Text>
        )}
        <ProblemAlert error={error} />
        {onCodeChange && (
          <TextInput
            label={codeLabel}
            value={code ?? ''}
            onChange={(e) => onCodeChange(e.currentTarget.value)}
            required
            data-testid="json-code"
          />
        )}
        <JsonInput
          label="JSON"
          value={text}
          onChange={setText}
          formatOnBlur
          // A fixed height: formatting on blur must not move the buttons below while they are being clicked.
          rows={18}
          validationError={t('common.invalidJson')}
          styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)', fontSize: 13 } }}
          data-testid="json-editor"
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            disabled={parsed === null || (onCodeChange !== undefined && !code?.trim())}
            loading={pending}
            onClick={() => parsed && onSubmit(parsed)}
            data-testid="json-submit"
          >
            {t('common.save')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
