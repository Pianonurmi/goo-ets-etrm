import {
  Alert,
  Button,
  Card,
  Group,
  Modal,
  Select,
  Stack,
  Switch,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { decimal, type Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { useInvalidateSettlement } from './hooks';
import type { VatRule } from './model/payments';
import { StatusBadge } from '@/shared/components/StatusBadge';

type Scope = Schema<'ProposeVatRule'>['scope'];
type Treatment = Schema<'ProposeVatRule'>['treatment'];
type Family = 'GUARANTEE_OF_ORIGIN' | 'EMISSION_ALLOWANCE';

const SCOPES: Scope[] = ['DOMESTIC', 'INTRA_EU', 'IMPORT', 'EXPORT', 'ANY'];
const TREATMENTS: Treatment[] = [
  'STANDARD',
  'REVERSE_CHARGE_DOMESTIC',
  'REVERSE_CHARGE_CROSS_BORDER',
  'OUT_OF_SCOPE',
  'EXEMPT',
];
const FAMILIES: Family[] = ['GUARANTEE_OF_ORIGIN', 'EMISSION_ALLOWANCE'];

function useFamilyOptions(withAny: boolean) {
  const { t } = useTranslation();
  return [
    ...(withAny ? [{ value: '*', label: t('settlement.anyFamily') }] : []),
    ...FAMILIES.map((value) => ({ value, label: t(`trades.families.${value}`) })),
  ];
}

/** A new rule, or a new version of a code: a draft until a second accountant approves it in the inbox. */
function ProposeRuleModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateSettlement();
  const families = useFamilyOptions(true);
  const [code, setCode] = useState('');
  const [description, setDescription] = useState('');
  const [family, setFamily] = useState('*');
  const [supplierCountry, setSupplierCountry] = useState('*');
  const [scope, setScope] = useState<Scope>('DOMESTIC');
  const [treatment, setTreatment] = useState<Treatment>('STANDARD');
  const [rate, setRate] = useState('0');
  const [validFrom, setValidFrom] = useState(dayjs().format('YYYY-MM-DD'));
  const [validTo, setValidTo] = useState('');
  const [legalText, setLegalText] = useState('');
  const propose = $api.useMutation('post', '/api/v1/settlement/vat-rules', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('settlement.ruleProposed'));
      void invalidate();
      onClose();
    },
  });
  const ready = code.trim() && description.trim() && validFrom && rate.trim();
  return (
    <Modal opened onClose={onClose} title={t('settlement.proposeRule')} size="lg">
      <Stack>
        <ProblemAlert error={propose.error} />
        <Text size="sm" c="dimmed">
          {t('settlement.proposeRuleHint')}
        </Text>
        <Group grow>
          <TextInput
            label={t('settlement.ruleCode')}
            value={code}
            onChange={(e) => setCode(e.currentTarget.value.toUpperCase())}
            required
            data-testid="rule-code"
          />
          <Select
            label={t('settlement.productFamily')}
            data={families}
            value={family}
            allowDeselect={false}
            onChange={(value) => value && setFamily(value)}
          />
          <TextInput
            label={t('settlement.supplierCountry')}
            description={t('settlement.anyCountryHint')}
            value={supplierCountry}
            maxLength={2}
            onChange={(e) => setSupplierCountry(e.currentTarget.value.toUpperCase())}
          />
        </Group>
        <TextInput
          label={t('settlement.ruleDescription')}
          value={description}
          onChange={(e) => setDescription(e.currentTarget.value)}
          required
          data-testid="rule-description"
        />
        <Group grow>
          <Select
            label={t('settlement.scope')}
            data={SCOPES.map((value) => ({ value, label: t(`settlement.scopes.${value}`) }))}
            value={scope}
            allowDeselect={false}
            onChange={(value) => value && setScope(value)}
          />
          <Select
            label={t('settlement.vatTreatment')}
            data={TREATMENTS.map((value) => ({ value, label: t(`settlement.treatments.${value}`) }))}
            value={treatment}
            allowDeselect={false}
            onChange={(value) => value && setTreatment(value)}
          />
          <TextInput
            label={t('settlement.vatRate')}
            inputMode="decimal"
            rightSection="%"
            value={rate}
            onChange={(e) => setRate(e.currentTarget.value)}
            data-testid="rule-rate"
          />
        </Group>
        <Group grow>
          <TextInput
            type="date"
            label={t('risk.validFrom')}
            value={validFrom}
            onChange={(e) => setValidFrom(e.currentTarget.value)}
            required
          />
          <TextInput
            type="date"
            label={t('settlement.validTo')}
            value={validTo}
            onChange={(e) => setValidTo(e.currentTarget.value)}
          />
        </Group>
        <Textarea
          label={t('settlement.legalText')}
          description={t('settlement.legalTextHint')}
          value={legalText}
          onChange={(e) => setLegalText(e.currentTarget.value)}
          autosize
        />
        <Button
          disabled={!ready}
          loading={propose.isPending}
          data-testid="rule-submit"
          onClick={() =>
            propose.mutate({
              body: {
                code: code.trim(),
                description: description.trim(),
                productFamily: family,
                supplierCountry: supplierCountry.trim() || '*',
                scope,
                treatment,
                rate: decimal(rate),
                validFrom,
                validTo: validTo || undefined,
                legalText: legalText.trim() || undefined,
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

/** Which rule would apply to a supply: for checking a change before it takes effect. */
function DeterminationTester() {
  const { t } = useTranslation();
  const families = useFamilyOptions(false);
  const [family, setFamily] = useState<Family>('GUARANTEE_OF_ORIGIN');
  const [supplier, setSupplier] = useState('FI');
  const [customer, setCustomer] = useState('SE');
  const [date, setDate] = useState(dayjs().format('YYYY-MM-DD'));
  const [asked, setAsked] = useState(false);
  const determination = $api.useQuery(
    'get',
    '/api/v1/settlement/vat-rules/determination',
    {
      params: {
        query: { productFamily: family, supplierCountry: supplier, customerCountry: customer, taxPointDate: date },
      },
    },
    { enabled: asked && supplier.length === 2 && customer.length === 2 && Boolean(date) },
  );
  const rule = determination.data?.rule;
  return (
    <Card withBorder>
      <Title order={5} mb="xs">
        {t('settlement.determination')}
      </Title>
      <Group align="flex-end">
        <Select
          label={t('settlement.productFamily')}
          data={families}
          value={family}
          allowDeselect={false}
          onChange={(value) => value && setFamily(value as Family)}
          w={200}
        />
        <TextInput
          label={t('settlement.supplierCountry')}
          value={supplier}
          maxLength={2}
          onChange={(e) => setSupplier(e.currentTarget.value.toUpperCase())}
          w={130}
          data-testid="determine-supplier"
        />
        <TextInput
          label={t('settlement.customerCountry')}
          value={customer}
          maxLength={2}
          onChange={(e) => setCustomer(e.currentTarget.value.toUpperCase())}
          w={130}
          data-testid="determine-customer"
        />
        <TextInput
          type="date"
          label={t('settlement.taxPointDate')}
          value={date}
          onChange={(e) => setDate(e.currentTarget.value)}
          w={170}
        />
        <Button
          variant="light"
          leftSection={<IconSearch size={16} />}
          onClick={() => {
            setAsked(true);
            void determination.refetch();
          }}
          data-testid="determine"
        >
          {t('settlement.determine')}
        </Button>
      </Group>
      {asked && determination.data && (
        <Alert color={rule ? 'teal' : 'orange'} mt="sm" data-testid="determination-result">
          {rule
            ? t('settlement.ruleApplies', {
                code: rule.code,
                version: rule.version,
                treatment: t(`settlement.treatments.${rule.treatment}`),
                rate: formatDecimal(rule.rate, 0, 2),
              })
            : determination.data.problem}
          {rule?.legalText && (
            <Text size="xs" mt={4}>
              {rule.legalText}
            </Text>
          )}
        </Alert>
      )}
      <ProblemAlert error={determination.error} />
    </Card>
  );
}

/** Approved VAT rules by product, supplier country and scope; changes go through four eyes. */
export function VatRulesTab() {
  const { t } = useTranslation();
  const [includeRetired, setIncludeRetired] = useState(false);
  const [proposing, setProposing] = useState(false);
  const rules = $api.useQuery('get', '/api/v1/settlement/vat-rules', { params: { query: { includeRetired } } });
  const columns = useMemo<ColDef<VatRule>[]>(
    () => [
      { field: 'code', headerName: t('settlement.ruleCode'), width: 150 },
      { field: 'version', headerName: t('settlement.version'), width: 90 },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 130,
        cellRenderer: (p: ICellRendererParams<VatRule>) =>
          // Upper case as before: the end-to-end test finds the proposed rule's row by "DRAFT".
          p.data && (
            <StatusBadge status={p.data.status === 'DRAFT' ? 'PENDING' : p.data.status} label={p.data.status} />
          ),
      },
      { field: 'description', headerName: t('settlement.ruleDescription'), flex: 1, minWidth: 260 },
      {
        field: 'productFamily',
        headerName: t('settlement.productFamily'),
        width: 170,
        valueFormatter: (p) => (p.value === '*' ? t('settlement.anyFamily') : t(`trades.families.${String(p.value)}`)),
      },
      { field: 'supplierCountry', headerName: t('settlement.supplierCountry'), width: 130 },
      {
        field: 'scope',
        headerName: t('settlement.scope'),
        width: 150,
        valueFormatter: (p) => t(`settlement.scopes.${String(p.value)}`),
      },
      {
        field: 'treatment',
        headerName: t('settlement.vatTreatment'),
        width: 210,
        valueFormatter: (p) => t(`settlement.treatments.${String(p.value)}`),
      },
      {
        field: 'rate',
        headerName: t('settlement.vatRate'),
        width: 100,
        type: 'rightAligned',
        valueFormatter: (p) => `${formatDecimal(p.value as number, 0, 2)} %`,
      },
      { field: 'validFrom', headerName: t('risk.validFrom'), width: 120 },
      { field: 'validTo', headerName: t('settlement.validTo'), width: 120 },
      { field: 'createdBy', headerName: t('settlement.createdBy'), width: 120 },
      { field: 'approvedBy', headerName: t('settlement.approvedByShort'), width: 120 },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="space-between">
        <Switch
          label={t('settlement.includeRetired')}
          checked={includeRetired}
          onChange={(e) => setIncludeRetired(e.currentTarget.checked)}
        />
        <Can op="settlement_proposeVatRule">
          <Button leftSection={<IconPlus size={16} />} onClick={() => setProposing(true)} data-testid="propose-rule">
            {t('settlement.proposeRule')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={rules.isPending} error={rules.error}>
        <DataGrid<VatRule> rowData={rules.data ?? []} columnDefs={columns} rowId={(row) => row.id} height={380} />
      </QueryState>
      <DeterminationTester />
      {proposing && <ProposeRuleModal onClose={() => setProposing(false)} />}
    </Stack>
  );
}
