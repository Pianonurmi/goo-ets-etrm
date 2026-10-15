import { Badge, Button, Group, Modal, Select, Stack, Text, TextInput } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { statusLabel } from '@/shared/components/StatusBadge';
import { useInvalidateReference } from '../PartyDetails';
import { Section } from '../Section';
import { type BookType, BOOK_TYPES } from './shared';

export function Books({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [adding, setAdding] = useState(false);
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [bookType, setBookType] = useState<BookType>('TRADING');
  const books = $api.useQuery('get', '/api/v1/reference/legal-entities/{id}/books', {
    params: { path: { id: entityId } },
  });
  const create = $api.useMutation('post', '/api/v1/reference/books', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      setAdding(false);
      void invalidate();
    },
  });
  return (
    <Section
      title={t('admin.books')}
      action={
        <Can op="referenceData_createBook">
          <Button
            size="compact-sm"
            variant="subtle"
            leftSection={<IconPlus size={14} />}
            onClick={() => setAdding(true)}
          >
            {t('admin.addBook')}
          </Button>
        </Can>
      }
    >
      <Stack gap={6}>
        {books.data?.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('admin.noBooks')}
          </Text>
        )}
        {(books.data ?? []).map((book) => (
          <Group key={book.id} gap="xs" wrap="nowrap" justify="space-between">
            <Text size="sm" truncate>
              <Text span ff="monospace" size="sm">
                {book.code}
              </Text>{' '}
              · {book.name}
            </Text>
            <Badge size="sm" variant="light" color="gray" tt="none" style={{ flexShrink: 0 }}>
              {statusLabel(book.bookType)}
            </Badge>
          </Group>
        ))}
      </Stack>
      <Modal opened={adding} onClose={() => setAdding(false)} title={t('admin.addBook')}>
        <Stack>
          <ProblemAlert error={create.error} />
          <Group grow>
            <TextInput
              label={t('marketData.code')}
              value={code}
              onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
            />
            <Select
              label={t('monthEnd.bookType')}
              data={BOOK_TYPES}
              value={bookType}
              allowDeselect={false}
              onChange={(value) => value && setBookType(value)}
            />
          </Group>
          <TextInput label={t('ets.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} />
          <Button
            disabled={!code.trim() || !name.trim()}
            loading={create.isPending}
            onClick={() => create.mutate({ body: { legalEntityId: entityId, code, name, bookType } })}
          >
            {t('ets.save')}
          </Button>
        </Stack>
      </Modal>
    </Section>
  );
}
