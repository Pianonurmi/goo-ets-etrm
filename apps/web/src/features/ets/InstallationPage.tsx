import { useUrlState } from '@/shared/useUrlState';
import {
  Alert,
  Button,
  Group,
  Modal,
  NumberInput,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
} from '@mantine/core';
import { IconCalendar, IconCertificate, IconFlame, IconPencil, IconSend } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';
import { $api } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { Can } from '@/shared/auth/Can';
import { DetailLayout } from '@/shared/components/DetailLayout';
import { KpiCard } from '@/shared/components/KpiCard';
import { notifySuccess } from '@/shared/components/notify';
import { PageHeader } from '@/shared/components/PageHeader';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { CardSkeleton } from '@/shared/components/Skeletons';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { oneOf, yearOr } from '@/shared/urlParams';
import { ComplianceStatusBadge, Figure, tonnes, useInvalidateEts } from './EtsPage';

type EmissionRecord = Schema<'EmissionRecordView'>;
type EmissionKind = Schema<'RecordEmissions'>['kind'];
type FreeAllocationStatus = NonNullable<Schema<'SetFreeAllocation'>['status']>;
type Installation = Schema<'InstallationView'>;

function RecordEmissionsModal({
  onClose,
  installationId,
  year,
}: {
  onClose: () => void;
  installationId: string;
  year: number;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateEts();
  const [kind, setKind] = useState<Exclude<EmissionKind, 'VERIFIED'>>('MONTHLY_ESTIMATE');
  const [month, setMonth] = useState<number>(Math.max(1, dayjs().month()));
  const [quantity, setQuantity] = useState<number | ''>('');
  const [note, setNote] = useState('');
  const record = $api.useMutation('post', '/api/v1/ets/installations/{id}/emissions', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('ets.emissionsRecorded'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('ets.recordEmissions')}>
      <Stack>
        <ProblemAlert error={record.error} />
        <Group grow>
          <Select
            label={t('ets.kind')}
            data={[
              { value: 'MONTHLY_ESTIMATE', label: t('ets.emissionKinds.MONTHLY_ESTIMATE') },
              { value: 'FORECAST', label: t('ets.emissionKinds.FORECAST') },
            ]}
            value={kind}
            allowDeselect={false}
            onChange={(value) => value && setKind(value)}
          />
          {kind === 'MONTHLY_ESTIMATE' && (
            <NumberInput label={t('ets.month')} value={month} onChange={(v) => setMonth(Number(v))} min={1} max={12} />
          )}
        </Group>
        <NumberInput
          label={t('ets.quantity')}
          value={quantity}
          onChange={(v) => setQuantity(v === '' ? '' : Number(v))}
          min={0}
          allowDecimal={false}
          suffix=" t"
          data-testid="emissions-quantity"
        />
        <TextInput label={t('marketData.note')} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
        <Button
          disabled={quantity === ''}
          loading={record.isPending}
          onClick={() =>
            record.mutate({
              params: { path: { id: installationId } },
              body: {
                complianceYear: year,
                kind,
                periodMonth: kind === 'MONTHLY_ESTIMATE' ? month : undefined,
                quantityTco2e: Number(quantity),
                note: note || undefined,
              },
            })
          }
          data-testid="record-emissions-submit"
        >
          {t('ets.recordEmissions')}
        </Button>
      </Stack>
    </Modal>
  );
}

function RecordVerifiedModal({
  onClose,
  installationId,
  year,
}: {
  onClose: () => void;
  installationId: string;
  year: number;
}) {
  const { t } = useTranslation();
  const invalidate = useInvalidateEts();
  const [quantity, setQuantity] = useState<number | ''>('');
  const [verifier, setVerifier] = useState('');
  const [verifiedOn, setVerifiedOn] = useState(dayjs().format('YYYY-MM-DD'));
  const [documentRef, setDocumentRef] = useState('');
  const record = $api.useMutation('post', '/api/v1/ets/installations/{id}/verified-emissions', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('ets.verifiedRecorded'));
      void invalidate();
      onClose();
    },
  });
  return (
    <Modal opened onClose={onClose} title={t('ets.recordVerified')}>
      <Stack>
        <ProblemAlert error={record.error} />
        <Text size="sm" c="dimmed">
          {t('risk.fourEyesHint')}
        </Text>
        <NumberInput
          label={t('ets.quantity')}
          value={quantity}
          onChange={(v) => setQuantity(v === '' ? '' : Number(v))}
          min={0}
          allowDecimal={false}
          suffix=" t"
        />
        <Group grow>
          <TextInput
            label={t('ets.verifier')}
            value={verifier}
            onChange={(e) => setVerifier(e.currentTarget.value)}
            required
          />
          <TextInput
            type="date"
            label={t('ets.verifiedOn')}
            value={verifiedOn}
            onChange={(e) => setVerifiedOn(e.currentTarget.value)}
          />
        </Group>
        <TextInput
          label={t('ets.documentRef')}
          value={documentRef}
          onChange={(e) => setDocumentRef(e.currentTarget.value)}
        />
        <Button
          disabled={quantity === '' || !verifier.trim()}
          loading={record.isPending}
          onClick={() =>
            record.mutate({
              params: { path: { id: installationId } },
              body: {
                complianceYear: year,
                quantityTco2e: Number(quantity),
                verifier,
                verifiedOn,
                documentRef: documentRef || undefined,
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

function EmissionsTab({ installationId, year }: { installationId: string; year: number }) {
  const { t } = useTranslation();
  const [recording, setRecording] = useState<'estimate' | 'verified' | null>(null);
  const emissions = $api.useQuery('get', '/api/v1/ets/installations/{id}/emissions', {
    params: { path: { id: installationId }, query: { year } },
  });
  const columns = useMemo<ColDef<EmissionRecord>[]>(
    () => [
      {
        field: 'kind',
        headerName: t('ets.kind'),
        width: 170,
        valueFormatter: (p) => t(`ets.emissionKinds.${String(p.value)}`),
      },
      { field: 'periodMonth', headerName: t('ets.month'), width: 120, sort: 'asc' },
      {
        field: 'quantityTco2e',
        headerName: t('ets.quantity'),
        width: 130,
        type: 'rightAligned',
        valueFormatter: (p) => tonnes(p.value as number),
      },
      {
        field: 'status',
        headerName: t('allocation.status'),
        width: 160,
        cellRenderer: (p: { data?: EmissionRecord }) => p.data && <StatusBadge status={p.data.status} />,
      },
      { field: 'verifier', headerName: t('ets.verifier'), width: 160 },
      { field: 'recordedBy', headerName: t('marketData.recordedBy'), width: 130 },
      {
        field: 'recordedAt',
        headerName: t('marketData.recordedAt'),
        width: 170,
        valueFormatter: (p) => formatDateTime(p.value as string),
      },
      { field: 'approvedBy', headerName: t('curves.signedOffBy'), width: 130 },
      { field: 'note', headerName: t('marketData.note'), flex: 1, minWidth: 160 },
    ],
    [t],
  );
  return (
    <Stack>
      <Group justify="flex-end" gap="xs">
        <Can op="etsCompliance_recordEmissions">
          <Button
            variant="default"
            leftSection={<IconFlame size={16} />}
            onClick={() => setRecording('estimate')}
            data-testid="record-emissions"
          >
            {t('ets.recordEmissions')}
          </Button>
        </Can>
        <Can op="etsCompliance_recordVerifiedEmissions">
          <Button
            variant="default"
            leftSection={<IconCertificate size={16} />}
            onClick={() => setRecording('verified')}
          >
            {t('ets.recordVerified')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={emissions.isPending} error={emissions.error}>
        <DataGrid<EmissionRecord>
          rowData={emissions.data ?? []}
          columnDefs={columns}
          rowId={(row) => row.id}
          height={400}
          overlayNoRowsTemplate={t('common.empty')}
        />
      </QueryState>
      {recording === 'estimate' && (
        <RecordEmissionsModal onClose={() => setRecording(null)} installationId={installationId} year={year} />
      )}
      {recording === 'verified' && (
        <RecordVerifiedModal onClose={() => setRecording(null)} installationId={installationId} year={year} />
      )}
    </Stack>
  );
}

function FreeAllocationTab({ installationId, year }: { installationId: string; year: number }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateEts();
  const [editing, setEditing] = useState(false);
  const allocation = $api.useQuery(
    'get',
    '/api/v1/ets/installations/{id}/free-allocation/{year}',
    { params: { path: { id: installationId, year } } },
    { retry: false },
  );
  const [allocated, setAllocated] = useState<number | ''>('');
  const [adjusted, setAdjusted] = useState<number | ''>('');
  const [status, setStatus] = useState<FreeAllocationStatus>('EXPECTED');
  const [note, setNote] = useState('');
  const save = $api.useMutation('put', '/api/v1/ets/installations/{id}/free-allocation/{year}', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('ets.allocationSaved'));
      void invalidate();
      setEditing(false);
    },
  });
  const open = () => {
    setAllocated(allocation.data?.allocated ?? '');
    setAdjusted(allocation.data?.adjusted ?? '');
    setStatus(allocation.data?.status ?? 'EXPECTED');
    setNote(allocation.data?.note ?? '');
    setEditing(true);
  };
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="etsCompliance_setFreeAllocation">
          <Button variant="default" leftSection={<IconPencil size={16} />} onClick={open}>
            {t('ets.setAllocation')}
          </Button>
        </Can>
      </Group>
      {allocation.isPending ? null : !allocation.data ? (
        <Alert color="gray">{t('ets.noAllocation', { year })}</Alert>
      ) : (
        <SimpleGrid cols={{ base: 2, md: 5 }}>
          <Figure label={t('ets.allocated')} value={tonnes(allocation.data.allocated)} />
          <Figure label={t('ets.adjusted')} value={tonnes(allocation.data.adjusted)} />
          <Figure label={t('ets.entitlement')} value={tonnes(allocation.data.entitlement)} />
          <Figure label={t('ets.received')} value={tonnes(allocation.data.received)} />
          <Figure label={t('ets.outstanding')} value={tonnes(allocation.data.outstanding)} />
        </SimpleGrid>
      )}
      {allocation.data?.note && (
        <Text size="sm" c="dimmed">
          {allocation.data.note}
        </Text>
      )}
      <Modal opened={editing} onClose={() => setEditing(false)} title={t('ets.setAllocation')}>
        <Stack>
          <ProblemAlert error={save.error} />
          <Group grow>
            <NumberInput
              label={t('ets.allocated')}
              value={allocated}
              onChange={(v) => setAllocated(v === '' ? '' : Number(v))}
              min={0}
              allowDecimal={false}
            />
            <NumberInput
              label={t('ets.adjusted')}
              description={t('ets.adjustedHint')}
              value={adjusted}
              onChange={(v) => setAdjusted(v === '' ? '' : Number(v))}
              min={0}
              allowDecimal={false}
            />
          </Group>
          <Select
            label={t('allocation.status')}
            data={(['EXPECTED', 'ISSUED', 'WITHHELD'] as const).map((value) => ({
              value,
              label: t(`ets.allocationStatus.${value}`),
            }))}
            value={status}
            allowDeselect={false}
            onChange={(value) => value && setStatus(value)}
          />
          <TextInput label={t('marketData.note')} value={note} onChange={(e) => setNote(e.currentTarget.value)} />
          <Button
            disabled={allocated === ''}
            loading={save.isPending}
            onClick={() =>
              save.mutate({
                params: { path: { id: installationId, year } },
                body: {
                  allocated: Number(allocated),
                  adjusted: adjusted === '' ? undefined : adjusted,
                  status,
                  note: note || undefined,
                },
              })
            }
          >
            {t('ets.save')}
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}

function SurrendersTab({ installation, year }: { installation: Installation; year: number }) {
  const { t } = useTranslation();
  const invalidate = useInvalidateEts();
  const [requesting, setRequesting] = useState(false);
  const [quantity, setQuantity] = useState<number | ''>('');
  const [product, setProduct] = useState<string | null>(installation.eligibleProducts[0] ?? null);
  const surrenders = $api.useQuery('get', '/api/v1/ets/installations/{id}/surrenders', {
    params: { path: { id: installation.id } },
  });
  const request = $api.useMutation('post', '/api/v1/ets/installations/{id}/surrenders', {
    meta: { notify: false },
    onSuccess: () => {
      notifySuccess(t('ets.surrenderRequested'));
      void invalidate();
      setRequesting(false);
    },
  });
  return (
    <Stack>
      <Group justify="flex-end">
        <Can op="etsCompliance_requestSurrender">
          <Button variant="default" leftSection={<IconSend size={16} />} onClick={() => setRequesting(true)}>
            {t('ets.requestSurrender')}
          </Button>
        </Can>
      </Group>
      <QueryState isPending={surrenders.isPending} error={surrenders.error}>
        {(surrenders.data ?? []).length === 0 ? (
          <Alert color="gray">{t('ets.noSurrenders')}</Alert>
        ) : (
          <Table withTableBorder striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('positions.complianceYear')}</Table.Th>
                <Table.Th>{t('trades.form.allowanceProduct')}</Table.Th>
                <Table.Th ta="right">{t('ets.quantity')}</Table.Th>
                <Table.Th>{t('allocation.status')}</Table.Th>
                <Table.Th>{t('risk.requestedBy')}</Table.Th>
                <Table.Th>{t('ets.requestedAt')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(surrenders.data ?? []).map((row) => (
                <Table.Tr key={row.id}>
                  <Table.Td>{row.complianceYear}</Table.Td>
                  <Table.Td>{row.productCode}</Table.Td>
                  <Table.Td ta="right">{tonnes(row.quantity)}</Table.Td>
                  <Table.Td>
                    <StatusBadge status={row.status} />
                  </Table.Td>
                  <Table.Td>{row.requestedBy}</Table.Td>
                  <Table.Td>{formatDateTime(row.requestedAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
      <Modal opened={requesting} onClose={() => setRequesting(false)} title={t('ets.requestSurrender')}>
        <Stack>
          <ProblemAlert error={request.error} />
          <Text size="sm" c="dimmed">
            {t('ets.surrenderHint', { year })}
          </Text>
          <Group grow>
            <Select
              label={t('trades.form.allowanceProduct')}
              data={installation.eligibleProducts}
              value={product}
              onChange={setProduct}
            />
            <NumberInput
              label={t('ets.quantity')}
              placeholder={t('ets.allOutstanding')}
              value={quantity}
              onChange={(v) => setQuantity(v === '' ? '' : Number(v))}
              min={1}
              allowDecimal={false}
            />
          </Group>
          <Button
            loading={request.isPending}
            onClick={() =>
              request.mutate({
                params: { path: { id: installation.id } },
                body: {
                  complianceYear: year,
                  productCode: product ?? undefined,
                  quantity: quantity === '' ? undefined : quantity,
                },
              })
            }
          >
            {t('ets.requestSurrender')}
          </Button>
        </Stack>
      </Modal>
    </Stack>
  );
}

/** One installation: the year's compliance position, master data, emissions, free allocation and surrenders. */
export function InstallationPage() {
  const { t } = useTranslation();
  const { installationId = '' } = useParams();
  const { params, set } = useUrlState();
  const year = yearOr(params.get('year'), new Date().getFullYear());
  const tab = oneOf(params.get('tab'), ['emissions', 'free-allocation', 'surrenders'] as const, 'emissions');
  const installation = $api.useQuery('get', '/api/v1/ets/installations/{id}', {
    params: { path: { id: installationId } },
  });
  const compliance = $api.useQuery('get', '/api/v1/ets/installations/{id}/compliance/{year}', {
    params: { path: { id: installationId, year } },
  });
  const setParam = (key: string, value: string | null) => set({ [key]: value });
  const breadcrumbs = [{ label: t('ets.title'), to: `/ets?year=${year}` }];
  const data = installation.data;
  if (!data) {
    return (
      <Stack gap={0}>
        <PageHeader breadcrumbs={breadcrumbs} title={t('positions.installation')} />
        <QueryState isPending={false} error={installation.error}>
          <CardSkeleton height={260} />
        </QueryState>
      </Stack>
    );
  }
  const position = compliance.data;
  return (
    <Stack>
      <PageHeader
        breadcrumbs={breadcrumbs}
        title={data.name}
        badge={
          <Group gap={6} wrap="nowrap">
            {position && <ComplianceStatusBadge status={position.status} />}
            <StatusBadge status={data.active ? 'KIND' : 'INACTIVE'} label={t(`ets.kinds.${data.kind}`)} />
          </Group>
        }
        description={`${data.eutlId} · ${data.country}${data.activityCode ? ` · ${t('ets.activityCode')} ${data.activityCode}` : ''}`}
        actions={
          <NumberInput
            aria-label={t('positions.complianceYear')}
            leftSection={<IconCalendar size={16} />}
            value={year}
            onChange={(value) => setParam('year', Number(value) ? String(value) : null)}
            w={130}
            min={2005}
          />
        }
      />
      <QueryState isPending={false} error={compliance.error}>
        <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" data-testid="installation-kpis">
          <KpiCard
            label={t('ets.outstanding')}
            value={position ? formatDecimal(position.outstanding) : '—'}
            unit="t"
            loading={!position}
            trend={position && t('ets.kpi.surrenderBy', { date: formatDate(position.surrenderDeadline) })}
            trendTone={position && position.outstanding > 0 ? 'warning' : 'neutral'}
          />
          <KpiCard
            label={t('positions.held')}
            value={position ? formatDecimal(position.held) : '—'}
            unit="t"
            loading={!position}
            trend={position && t('ets.kpi.surrendered', { tonnes: formatDecimal(position.surrendered) })}
          />
          <KpiCard
            label={t('ets.net')}
            value={position ? formatDecimal(position.net) : '—'}
            unit="t"
            loading={!position}
            trend={position && t(position.net < 0 ? 'ets.kpi.short' : 'ets.kpi.covered')}
            trendTone={position ? (position.net < 0 ? 'down' : 'up') : 'neutral'}
          />
          <KpiCard
            label={t('positions.emissions')}
            value={position ? formatDecimal(position.emissions) : '—'}
            unit="t"
            loading={!position}
            trend={position && t(`ets.basis.${position.emissionsBasis}`)}
          />
        </SimpleGrid>
      </QueryState>
      <DetailLayout
        summary={[
          { label: t('ets.eutlId'), value: data.eutlId },
          { label: t('ets.permitId'), value: data.permitId ?? '–' },
          { label: t('ets.eligibleProducts'), value: data.eligibleProducts.join(', ') },
          {
            label: t('risk.validity'),
            value: `${formatDate(data.validFrom)} – ${data.validTo ? formatDate(data.validTo) : '∞'}`,
          },
          {
            label: t('ets.monitoringPlan'),
            value: [data.monitoringPlanRef, data.monitoringPlanVersion].filter(Boolean).join(' v') || '–',
          },
        ]}
      >
        <Tabs
          value={tab}
          onChange={(value) => setParam('tab', value === 'emissions' ? null : value)}
          keepMounted={false}
        >
          <Tabs.List>
            <Tabs.Tab value="emissions">{t('ets.tabs.emissions')}</Tabs.Tab>
            <Tabs.Tab value="free-allocation">{t('ets.tabs.freeAllocation')}</Tabs.Tab>
            <Tabs.Tab value="surrenders">{t('ets.tabs.surrenders')}</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="emissions" pt="md">
            <EmissionsTab installationId={installationId} year={year} />
          </Tabs.Panel>
          <Tabs.Panel value="free-allocation" pt="md">
            <FreeAllocationTab installationId={installationId} year={year} />
          </Tabs.Panel>
          <Tabs.Panel value="surrenders" pt="md">
            <SurrendersTab installation={data} year={year} />
          </Tabs.Panel>
        </Tabs>
      </DetailLayout>
    </Stack>
  );
}
