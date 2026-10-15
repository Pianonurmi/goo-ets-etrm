import { Badge, Button, Group, Modal, Select, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { statusLabel } from '@/shared/components/StatusBadge';
import { defaultBookAssignable } from '../model/referenceEdits';
import { useInvalidateReference } from '../PartyDetails';
import { Section } from '../Section';
import { useRegistries } from './hooks';
import { type AccountType, ACCOUNT_TYPES, ActiveBadge } from './shared';

export const NO_BOOK = 'NONE';

/** The book that inventory on an own registry account belongs to (position limits); middle or back office set it. */
export function DefaultBook({ account, entityId }: { account: Schema<'RegistryAccountView'>; entityId: string }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const canAssign = useCan('referenceData_assignDefaultBook');
  const books = $api.useQuery('get', '/api/v1/reference/legal-entities/{id}/books', {
    params: { path: { id: entityId } },
  });
  const assign = $api.useMutation('put', '/api/v1/reference/registry-accounts/{id}/default-book', {
    onSuccess: () => {
      notifySuccess(t('admin.defaultBookSaved', { account: `${account.registryCode}:${account.accountNumber}` }));
      void invalidate();
    },
  });
  const current = (books.data ?? []).find((book) => book.id === account.defaultBookId);
  if (!canAssign) {
    return (
      <Text size="sm" c="dimmed" data-testid={`default-book-${account.id}`}>
        {t('admin.defaultBook')}: {current?.code ?? t('admin.noDefaultBook')}
      </Text>
    );
  }
  return (
    <Select
      size="xs"
      w={220}
      aria-label={t('admin.defaultBook')}
      placeholder={t('admin.defaultBook')}
      data={[
        { value: NO_BOOK, label: t('admin.noDefaultBook') },
        ...(books.data ?? [])
          .filter((book) => book.active || book.id === account.defaultBookId)
          .map((book) => ({ value: book.id, label: `${book.code} · ${book.name}` })),
      ]}
      value={account.defaultBookId ?? NO_BOOK}
      allowDeselect={false}
      disabled={assign.isPending}
      onChange={(value) =>
        value &&
        assign.mutate({
          params: { path: { id: account.id } },
          body: { bookId: value === NO_BOOK ? null : value },
        })
      }
      data-testid={`default-book-${account.id}`}
    />
  );
}

export function RegistryAccounts({
  holderType,
  holderId,
}: {
  holderType: 'LEGAL_ENTITY' | 'COUNTERPARTY';
  holderId: string;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const registries = useRegistries();
  const [adding, setAdding] = useState(false);
  const [registryCode, setRegistryCode] = useState<string | null>(null);
  const [accountNumber, setAccountNumber] = useState('');
  const [name, setName] = useState('');
  const [accountType, setAccountType] = useState<AccountType>('HOLDING');
  const [reason, setReason] = useState('');
  const accounts = $api.useQuery('get', '/api/v1/reference/registry-accounts', {
    params: { query: { holderType, holderId } },
  });
  const done = () => {
    notifySuccess(holderType === 'COUNTERPARTY' ? t('admin.changeRequested') : t('admin.saved'));
    setAdding(false);
    void invalidate();
  };
  const create = $api.useMutation('post', '/api/v1/reference/registry-accounts', {
    meta: { notify: false },
    onSuccess: done,
  });
  const request = $api.useMutation('post', '/api/v1/reference/counterparties/{id}/registry-accounts/change-requests', {
    meta: { notify: false },
    onSuccess: done,
  });
  const submit = () =>
    holderType === 'LEGAL_ENTITY'
      ? create.mutate({
          body: { holderType, holderId, registryCode: registryCode ?? '', accountNumber, name, accountType },
        })
      : request.mutate({
          params: { path: { id: holderId } },
          body: { action: 'CREATE', registryCode, accountNumber, name, accountType, reason },
        });
  return (
    <Section
      title={t('admin.registryAccounts')}
      action={
        <Can
          op={
            holderType === 'LEGAL_ENTITY'
              ? 'referenceData_createRegistryAccount'
              : 'changeRequest_requestCounterpartyAccountChange'
          }
        >
          <Button
            size="compact-sm"
            variant="subtle"
            leftSection={<IconPlus size={14} />}
            onClick={() => setAdding(true)}
          >
            {t('admin.addAccount')}
          </Button>
        </Can>
      }
    >
      <Stack gap="xs">
        {accounts.data?.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('admin.noAccounts')}
          </Text>
        )}
        {(accounts.data ?? []).map((account) => (
          <Group
            key={account.id}
            gap="sm"
            justify="space-between"
            wrap="nowrap"
            className="etrm-task-row"
            data-testid={`registry-account-${account.id}`}
          >
            <Stack gap={2} miw={0}>
              <Group gap={6} wrap="nowrap">
                <Text size="sm" fw={500} truncate>
                  {account.name}
                </Text>
                {account.trusted && (
                  <Badge size="xs" variant="light" tt="none">
                    {t('admin.trusted')}
                  </Badge>
                )}
                {!account.active && <ActiveBadge active={false} />}
              </Group>
              <Text size="xs" c="dimmed" truncate>
                <Text span ff="monospace" size="xs">
                  {account.registryCode}:{account.accountNumber}
                </Text>{' '}
                · {statusLabel(account.accountType)}
              </Text>
            </Stack>
            {defaultBookAssignable(account) && <DefaultBook account={account} entityId={holderId} />}
          </Group>
        ))}
      </Stack>
      <Modal opened={adding} onClose={() => setAdding(false)} title={t('admin.addAccount')}>
        <Stack>
          <ProblemAlert error={create.error ?? request.error} />
          {holderType === 'COUNTERPARTY' && (
            <Text size="sm" c="dimmed">
              {t('risk.fourEyesHint')}
            </Text>
          )}
          <Select
            label={t('admin.registry')}
            data={(registries.data ?? []).map((r) => ({ value: r.code, label: `${r.code} · ${r.name}` }))}
            value={registryCode}
            onChange={setRegistryCode}
          />
          <Group grow>
            <TextInput
              label={t('admin.accountNumber')}
              value={accountNumber}
              onChange={(e) => setAccountNumber(e.currentTarget.value)}
            />
            <Select
              label={t('admin.accountType')}
              data={ACCOUNT_TYPES}
              value={accountType}
              allowDeselect={false}
              onChange={(value) => value && setAccountType(value)}
            />
          </Group>
          <TextInput label={t('ets.name')} value={name} onChange={(e) => setName(e.currentTarget.value)} />
          {holderType === 'COUNTERPARTY' && (
            <Textarea
              label={t('risk.reason')}
              value={reason}
              onChange={(e) => setReason(e.currentTarget.value)}
              autosize
            />
          )}
          <Button
            disabled={
              !registryCode ||
              !accountNumber.trim() ||
              !name.trim() ||
              (holderType === 'COUNTERPARTY' && !reason.trim())
            }
            loading={create.isPending || request.isPending}
            onClick={submit}
          >
            {holderType === 'COUNTERPARTY' ? t('risk.sendForApproval') : t('ets.save')}
          </Button>
        </Stack>
      </Modal>
    </Section>
  );
}
