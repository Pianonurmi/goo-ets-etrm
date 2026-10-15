import { Alert, Button, Group, Modal, Select, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useCounterparties } from '@/features/trades/hooks';
import { $api } from '@/shared/api/client';
import { decimal } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { Money } from '@/shared/components/Figures';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate } from '@/shared/format/format';
import { useInvalidateRisk } from './RequestLimitModal';
import { type CreditSupportKind } from './shared';

export const CREDIT_SUPPORT_KINDS: CreditSupportKind[] = [
  'GUARANTEE_RECEIVED',
  'LETTER_OF_CREDIT_RECEIVED',
  'CASH_RECEIVED',
  'CASH_POSTED',
];

export function RequestCreditSupportModal({
  onClose,
  entityId,
  counterpartyId,
}: {
  onClose: () => void;
  entityId: string;
  counterpartyId: string;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateRisk();
  const [kind, setKind] = useState<CreditSupportKind>('GUARANTEE_RECEIVED');
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [validFrom, setValidFrom] = useState(dayjs().format('YYYY-MM-DD'));
  const [validTo, setValidTo] = useState('');
  const [reference, setReference] = useState('');
  const request = $api.useMutation('post', '/api/v1/risk/credit-support', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('risk.creditSupportRequested'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('risk.requestCreditSupport')}>
      <Stack>
        <ProblemAlert error={request.error} />
        <Text size="sm" c="dimmed">
          {t('risk.fourEyesHint')}
        </Text>
        <Select
          label={t('risk.kind')}
          data={CREDIT_SUPPORT_KINDS.map((value) => ({ value, label: t(`risk.creditKinds.${value}`) }))}
          value={kind}
          allowDeselect={false}
          onChange={(value) => value && setKind(value)}
        />
        <Group grow>
          <TextInput
            label={t('risk.amount')}
            inputMode="decimal"
            value={amount}
            onChange={(e) => setAmount(e.currentTarget.value)}
            required
          />
          <TextInput
            label={t('trades.form.currency')}
            value={currency}
            maxLength={3}
            onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
          />
        </Group>
        <Group grow>
          <TextInput
            type="date"
            label={t('risk.validFrom')}
            value={validFrom}
            onChange={(e) => setValidFrom(e.currentTarget.value)}
          />
          <TextInput
            type="date"
            label={t('risk.validTo')}
            value={validTo}
            onChange={(e) => setValidTo(e.currentTarget.value)}
          />
        </Group>
        <TextInput
          label={t('risk.reference')}
          value={reference}
          onChange={(e) => setReference(e.currentTarget.value)}
        />
        <Button
          disabled={!amount.trim()}
          loading={request.isPending}
          onClick={() =>
            request.mutate({
              body: {
                legalEntityId: entityId,
                counterpartyId,
                kind,
                amount: decimal(amount),
                currency,
                validFrom,
                validTo: validTo || undefined,
                reference: reference || undefined,
              },
            })
          }
        >
          {t('risk.sendForApproval')}
        </Button>
      </Stack>
    </Modal>
  );
}

export function CreditSupportTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const [picked, setPicked] = useState<string | null>(null);
  const [requesting, setRequesting] = useState(false);
  const counterpartyId = picked ?? counterparties.list[0]?.id ?? null;
  const support = $api.useQuery(
    'get',
    '/api/v1/risk/credit-support',
    { params: { query: { counterpartyId: counterpartyId ?? '' } } },
    { enabled: counterpartyId !== null },
  );
  const rows = (support.data ?? []).filter((row) => row.legalEntityId == null || row.legalEntityId === entityId);
  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <Select
          label={t('trades.columns.counterparty')}
          data={counterparties.list.map((c) => ({ value: c.id, label: `${c.code} · ${c.name}` }))}
          value={counterpartyId}
          onChange={setPicked}
          searchable
          w={360}
          data-testid="credit-support-counterparty"
        />
        {counterpartyId && (
          <Can op="risk_requestCreditSupport">
            <Button leftSection={<IconPlus size={16} />} onClick={() => setRequesting(true)}>
              {t('risk.requestCreditSupport')}
            </Button>
          </Can>
        )}
      </Group>
      <QueryState isPending={counterpartyId !== null && support.isPending} error={support.error}>
        {rows.length === 0 ? (
          <Alert color="gray">{t('risk.noCreditSupport')}</Alert>
        ) : (
          <Table withTableBorder striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('risk.kind')}</Table.Th>
                <Table.Th ta="right">{t('risk.amount')}</Table.Th>
                <Table.Th>{t('risk.validFrom')}</Table.Th>
                <Table.Th>{t('risk.validTo')}</Table.Th>
                <Table.Th>{t('risk.reference')}</Table.Th>
                <Table.Th>{t('allocation.status')}</Table.Th>
                <Table.Th>{t('risk.requestedBy')}</Table.Th>
                <Table.Th>{t('curves.signedOffBy')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((row) => (
                <Table.Tr key={row.id}>
                  <Table.Td>{t(`risk.creditKinds.${row.kind}`)}</Table.Td>
                  <Table.Td ta="right">
                    <Money value={row.amount} currency={row.currency} />
                  </Table.Td>
                  <Table.Td>{formatDate(row.validFrom)}</Table.Td>
                  <Table.Td>{formatDate(row.validTo)}</Table.Td>
                  <Table.Td>{row.reference}</Table.Td>
                  <Table.Td>
                    <StatusBadge status={row.status} />
                  </Table.Td>
                  <Table.Td>{row.requestedBy}</Table.Td>
                  <Table.Td>{row.approvedBy}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      {requesting && counterpartyId && (
        <RequestCreditSupportModal
          onClose={() => setRequesting(false)}
          entityId={entityId}
          counterpartyId={counterpartyId}
        />
      )}
    </Stack>
  );
}
