import {
  Alert,
  Button,
  Card,
  Group,
  Modal,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { decimal, type Body } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { statusLabel } from '@/shared/components/StatusBadge';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { useInvalidateMonthEnd } from './hooks';

export type PolicyChange = Body<'RequestPolicyChange'>;

export type BookType = NonNullable<PolicyChange['bookType']>;

export type Treatment = NonNullable<PolicyChange['treatment']>;

export const BOOK_TYPES: BookType[] = ['TRADING', 'OWN_USE', 'HEDGING', 'COMPLIANCE'];

export const TREATMENTS: Treatment[] = ['POST_PL', 'POST_OCI', 'DISCLOSE_ONLY'];

export function PolicyChangeModal({ entityId, onClose }: { entityId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateMonthEnd();
  const [kind, setKind] = useState<PolicyChange['kind']>('MTM');
  const [bookType, setBookType] = useState<BookType>('TRADING');
  const [treatment, setTreatment] = useState<Treatment>('POST_PL');
  const [goo, setGoo] = useState('');
  const [eua, setEua] = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [expiryDays, setExpiryDays] = useState<number | ''>('');
  const [reason, setReason] = useState('');
  const request = $api.useMutation('post', '/api/v1/ledger/period-end/policy-changes', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('monthEnd.policyRequested'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('monthEnd.requestPolicy')}>
      <Stack>
        <ProblemAlert error={request.error} />
        <Text size="sm" c="dimmed">
          {t('risk.fourEyesHint')}
        </Text>
        <Select
          label={t('marketData.kind')}
          data={[
            { value: 'MTM', label: t('monthEnd.mtmPolicy') },
            { value: 'NRV', label: t('monthEnd.nrvPolicy') },
          ]}
          value={kind}
          allowDeselect={false}
          onChange={(value) => value && setKind(value)}
        />
        {kind === 'MTM' ? (
          <Group grow>
            <Select
              label={t('monthEnd.bookType')}
              data={BOOK_TYPES}
              value={bookType}
              allowDeselect={false}
              onChange={(value) => value && setBookType(value)}
            />
            <Select
              label={t('monthEnd.treatment')}
              data={TREATMENTS.map((value) => ({ value, label: t(`monthEnd.treatments.${value}`) }))}
              value={treatment}
              allowDeselect={false}
              onChange={(value) => value && setTreatment(value)}
            />
          </Group>
        ) : (
          <SimpleGrid cols={2}>
            <TextInput
              label={t('monthEnd.gooCostToSell')}
              inputMode="decimal"
              value={goo}
              onChange={(e) => setGoo(e.currentTarget.value)}
            />
            <TextInput
              label={t('monthEnd.euaCostToSell')}
              inputMode="decimal"
              value={eua}
              onChange={(e) => setEua(e.currentTarget.value)}
            />
            <TextInput
              label={t('trades.form.currency')}
              value={currency}
              maxLength={3}
              onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
            />
            <NumberInput
              label={t('monthEnd.expiryDays')}
              value={expiryDays}
              onChange={(v) => setExpiryDays(v === '' ? '' : Number(v))}
              min={0}
              allowDecimal={false}
            />
          </SimpleGrid>
        )}
        <Textarea
          label={t('risk.reason')}
          value={reason}
          onChange={(e) => setReason(e.currentTarget.value)}
          autosize
          minRows={2}
        />
        <Button
          disabled={!reason.trim()}
          loading={request.isPending}
          onClick={() =>
            request.mutate({
              body:
                kind === 'MTM'
                  ? { kind, legalEntityId: entityId, bookType, treatment, reason }
                  : {
                      kind,
                      legalEntityId: entityId,
                      gooCostToSellPerMwh: goo ? decimal(goo) : undefined,
                      allowanceCostToSell: eua ? decimal(eua) : undefined,
                      currency,
                      expiryDays: expiryDays === '' ? undefined : expiryDays,
                      reason,
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

export function Policies({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  const [requesting, setRequesting] = useState(false);
  const mtm = $api.useQuery('get', '/api/v1/ledger/period-end/policies/mtm', {
    params: { query: { legalEntityId: entityId } },
  });
  const nrv = $api.useQuery(
    'get',
    '/api/v1/ledger/period-end/policies/nrv',
    { params: { query: { legalEntityId: entityId } } },
    { retry: false },
  );
  const changes = $api.useQuery('get', '/api/v1/ledger/period-end/policy-changes', {
    params: { query: { status: 'PENDING' } },
  });
  const signed = (by: string | null | undefined, at: string | null | undefined) =>
    by ? t('curves.signedOffByWho', { who: by, at: formatDateTime(at) }) : t('curves.notSignedOff');
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="periodEnd_requestPolicyChange">
          <Button variant="light" onClick={() => setRequesting(true)}>
            {t('monthEnd.requestPolicy')}
          </Button>
        </Can>
      </Group>
      {(changes.data ?? []).length > 0 && (
        <Alert color="yellow">{t('monthEnd.pendingPolicies', { count: (changes.data ?? []).length })}</Alert>
      )}
      <Card withBorder>
        <Title order={5} mb="sm">
          {t('monthEnd.mtmPolicy')}
        </Title>
        <QueryState isPending={mtm.isPending} error={mtm.error}>
          <Table striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('monthEnd.bookType')}</Table.Th>
                <Table.Th>{t('monthEnd.treatment')}</Table.Th>
                <Table.Th>{t('monthEnd.scope')}</Table.Th>
                <Table.Th>{t('curves.signedOffBy')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(mtm.data ?? []).map((policy) => (
                <Table.Tr key={policy.id}>
                  <Table.Td>{statusLabel(policy.bookType)}</Table.Td>
                  <Table.Td>{t(`monthEnd.treatments.${policy.treatment}`)}</Table.Td>
                  <Table.Td>{policy.legalEntityId ? t('monthEnd.entityPolicy') : t('monthEnd.groupPolicy')}</Table.Td>
                  <Table.Td>{signed(policy.signedOffBy, policy.signedOffAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </QueryState>
      </Card>
      <Card withBorder>
        <Title order={5} mb="sm">
          {t('monthEnd.nrvPolicy')}
        </Title>
        {nrv.data ? (
          <SimpleGrid cols={{ base: 2, md: 4 }}>
            <Text size="sm">
              {t('monthEnd.gooCostToSell')}: {formatDecimal(nrv.data.gooCostToSellPerMwh, 2, 4)} {nrv.data.currency}
            </Text>
            <Text size="sm">
              {t('monthEnd.euaCostToSell')}: {formatDecimal(nrv.data.allowanceCostToSell, 2, 4)} {nrv.data.currency}
            </Text>
            <Text size="sm">
              {t('monthEnd.expiryDays')}: {nrv.data.expiryDays}
            </Text>
            <Text size="sm">{signed(nrv.data.signedOffBy, nrv.data.signedOffAt)}</Text>
          </SimpleGrid>
        ) : (
          <Text size="sm" c="dimmed">
            {t('common.empty')}
          </Text>
        )}
      </Card>
      {requesting && <PolicyChangeModal entityId={entityId} onClose={() => setRequesting(false)} />}
    </Stack>
  );
}
