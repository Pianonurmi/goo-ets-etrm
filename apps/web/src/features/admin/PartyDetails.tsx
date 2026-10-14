import { Badge, Button, Group, Modal, Select, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { IconPlus } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import type { Body } from '@/shared/api/types';
import { Can, useCan } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate } from '@/shared/format/format';
import { Section } from './Section';

export type PartyType = 'LEGAL_ENTITY' | 'COUNTERPARTY';
type Address = Body<'UpsertAddress'>;

export function useInvalidateReference() {
  const queryClient = useQueryClient();
  return () =>
    queryClient.invalidateQueries({
      predicate: (q) =>
        typeof q.queryKey[1] === 'string' &&
        (q.queryKey[1].startsWith('/api/v1/reference') ||
          q.queryKey[1].startsWith('/api/v1/taxonomy') ||
          q.queryKey[1].startsWith('/api/v1/products') ||
          q.queryKey[1].startsWith('/api/v1/workflow')),
    });
}

function AddressModal({ partyType, partyId, onClose }: { partyType: PartyType; partyId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [value, setValue] = useState<Address>({
    addressType: 'REGISTERED',
    line1: '',
    line2: '',
    postalCode: '',
    city: '',
    country: '',
  });
  const save = $api.useMutation('put', '/api/v1/reference/parties/{partyType}/{partyId}/addresses', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      void invalidate();
      onClose();
    },
  });
  const set = (patch: Partial<Address>) => setValue({ ...value, ...patch });
  return (
    <Modal opened onClose={onClose} title={t('admin.address')}>
      <Stack>
        <ProblemAlert error={save.error} />
        <Select
          label={t('marketData.kind')}
          data={[
            { value: 'REGISTERED', label: t('admin.addressTypes.REGISTERED') },
            { value: 'BILLING', label: t('admin.addressTypes.BILLING') },
          ]}
          value={value.addressType}
          allowDeselect={false}
          onChange={(type) => type && set({ addressType: type })}
        />
        <TextInput
          label={t('admin.line1')}
          value={value.line1}
          onChange={(e) => set({ line1: e.currentTarget.value })}
        />
        <TextInput
          label={t('admin.line2')}
          value={value.line2 ?? ''}
          onChange={(e) => set({ line2: e.currentTarget.value })}
        />
        <Group grow>
          <TextInput
            label={t('admin.postalCode')}
            value={value.postalCode}
            onChange={(e) => set({ postalCode: e.currentTarget.value })}
          />
          <TextInput
            label={t('admin.city')}
            value={value.city}
            onChange={(e) => set({ city: e.currentTarget.value })}
          />
          <TextInput
            label={t('ets.country')}
            value={value.country}
            maxLength={2}
            onChange={(e) => set({ country: e.currentTarget.value.toUpperCase() })}
          />
        </Group>
        <Button
          disabled={!value.line1.trim() || !value.city.trim() || value.country.length !== 2}
          loading={save.isPending}
          onClick={() =>
            save.mutate({
              params: { path: { partyType, partyId } },
              body: { ...value, line2: value.line2 || undefined },
            })
          }
        >
          {t('ets.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

function VatModal({ partyType, partyId, onClose }: { partyType: PartyType; partyId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [country, setCountry] = useState('');
  const [vatNumber, setVatNumber] = useState('');
  const [validFrom, setValidFrom] = useState(dayjs().format('YYYY-MM-DD'));
  const save = $api.useMutation('put', '/api/v1/reference/parties/{partyType}/{partyId}/vat-registrations', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('admin.saved'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('admin.vat')}>
      <Stack>
        <ProblemAlert error={save.error} />
        <Group grow>
          <TextInput
            label={t('ets.country')}
            value={country}
            maxLength={2}
            onChange={(e) => setCountry(e.currentTarget.value.toUpperCase())}
          />
          <TextInput
            label={t('admin.vatNumber')}
            value={vatNumber}
            onChange={(e) => setVatNumber(e.currentTarget.value)}
          />
          <TextInput
            type="date"
            label={t('risk.validFrom')}
            value={validFrom}
            onChange={(e) => setValidFrom(e.currentTarget.value)}
          />
        </Group>
        <Button
          disabled={country.length !== 2 || !vatNumber.trim()}
          loading={save.isPending}
          onClick={() =>
            save.mutate({ params: { path: { partyType, partyId } }, body: { country, vatNumber, validFrom } })
          }
        >
          {t('ets.save')}
        </Button>
      </Stack>
    </Modal>
  );
}

function BankAccountModal({
  partyType,
  partyId,
  onClose,
}: {
  partyType: PartyType;
  partyId: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateReference();
  const [iban, setIban] = useState('');
  const [bic, setBic] = useState('');
  const [accountName, setAccountName] = useState('');
  const [currency, setCurrency] = useState('EUR');
  const [reason, setReason] = useState('');
  const request = $api.useMutation(
    'post',
    '/api/v1/reference/parties/{partyType}/{partyId}/bank-accounts/change-requests',
    {
      meta: { notify: false },
      onSuccess: () => {
        notifySuccess(t('admin.changeRequested'));
        void invalidate();
        onClose();
      },
    },
  );
  return (
    <Modal opened onClose={onClose} title={t('admin.addBankAccount')}>
      <Stack>
        <ProblemAlert error={request.error} />
        <Text size="sm" c="dimmed">
          {t('risk.fourEyesHint')}
        </Text>
        <TextInput label="IBAN" value={iban} onChange={(e) => setIban(e.currentTarget.value.toUpperCase())} />
        <Group grow>
          <TextInput label="BIC" value={bic} onChange={(e) => setBic(e.currentTarget.value.toUpperCase())} />
          <TextInput
            label={t('trades.form.currency')}
            value={currency}
            maxLength={3}
            onChange={(e) => setCurrency(e.currentTarget.value.toUpperCase())}
          />
        </Group>
        <TextInput
          label={t('admin.accountName')}
          value={accountName}
          onChange={(e) => setAccountName(e.currentTarget.value)}
        />
        <Textarea label={t('risk.reason')} value={reason} onChange={(e) => setReason(e.currentTarget.value)} autosize />
        <Button
          disabled={!iban.trim() || !reason.trim()}
          loading={request.isPending}
          onClick={() =>
            request.mutate({
              params: { path: { partyType, partyId } },
              body: {
                action: 'CREATE',
                iban,
                bic: bic || undefined,
                accountName: accountName || undefined,
                currency,
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

/** Addresses, VAT registrations and bank accounts of a legal entity or counterparty. */
export function PartyDetails({ partyType, partyId }: { partyType: PartyType; partyId: string }) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState<'address' | 'vat' | 'bank' | null>(null);
  const path = { params: { path: { partyType, partyId } } };
  const addresses = $api.useQuery('get', '/api/v1/reference/parties/{partyType}/{partyId}/addresses', path);
  const vat = $api.useQuery('get', '/api/v1/reference/parties/{partyType}/{partyId}/vat-registrations', path);
  // Payment details are shown only to the roles that maintain or pay them; the API refuses everyone else.
  const seesBanks = useCan('partyDetails_bankAccounts');
  const banks = $api.useQuery('get', '/api/v1/reference/parties/{partyType}/{partyId}/bank-accounts', path, {
    enabled: seesBanks,
  });
  const add = (op: 'address' | 'vat' | 'bank', permission: Parameters<typeof Can>[0]['op'], label: string) => (
    <Can op={permission}>
      <Button size="compact-sm" variant="subtle" leftSection={<IconPlus size={14} />} onClick={() => setEditing(op)}>
        {label}
      </Button>
    </Can>
  );
  return (
    <Stack>
      <Section
        title={t('admin.addresses')}
        action={add('address', 'partyDetails_upsertAddress', t('admin.setAddress'))}
        empty={addresses.data?.length === 0 ? t('admin.noAddresses') : null}
      >
        <Stack gap={6}>
          {(addresses.data ?? []).map((address) => (
            <Group key={address.addressType} gap="xs" wrap="nowrap" align="flex-start">
              <Badge size="sm" variant="light" color="gray" tt="none" style={{ flexShrink: 0 }}>
                {t(`admin.addressTypes.${address.addressType}`)}
              </Badge>
              <Text size="sm">{address.text}</Text>
            </Group>
          ))}
        </Stack>
      </Section>
      <Section
        title={t('admin.vatRegistrations')}
        action={add('vat', 'partyDetails_upsertVatRegistration', t('admin.addVat'))}
        empty={vat.data?.length === 0 ? t('admin.noVat') : null}
      >
        <Stack gap={6}>
          {(vat.data ?? []).map((row) => (
            <Group key={row.id} gap="xs" justify="space-between" wrap="nowrap">
              <Text size="sm" ff="monospace">
                {row.country} {row.vatNumber}
              </Text>
              <Text size="xs" c="dimmed">
                {formatDate(row.validFrom)} – {row.validTo ? formatDate(row.validTo) : '∞'}
              </Text>
            </Group>
          ))}
        </Stack>
      </Section>
      {seesBanks && (
        <Section
          title={t('admin.bankAccounts')}
          action={add('bank', 'partyDetails_requestBankAccountChange', t('admin.addBankAccount'))}
          empty={banks.data?.length === 0 ? t('admin.noBankAccounts') : null}
        >
          <Table fz="sm" verticalSpacing={6}>
            <Table.Tbody>
              {(banks.data ?? []).map((row) => (
                <Table.Tr key={row.id}>
                  <Table.Td ff="monospace">{row.iban}</Table.Td>
                  <Table.Td>{row.bic}</Table.Td>
                  <Table.Td>{row.currency}</Table.Td>
                  <Table.Td>
                    <StatusBadge
                      status={row.active ? 'ACTIVE' : 'INACTIVE'}
                      label={row.active ? t('admin.active') : t('admin.inactive')}
                    />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Section>
      )}
      {editing === 'address' && (
        <AddressModal partyType={partyType} partyId={partyId} onClose={() => setEditing(null)} />
      )}
      {editing === 'vat' && <VatModal partyType={partyType} partyId={partyId} onClose={() => setEditing(null)} />}
      {editing === 'bank' && (
        <BankAccountModal partyType={partyType} partyId={partyId} onClose={() => setEditing(null)} />
      )}
    </Stack>
  );
}
