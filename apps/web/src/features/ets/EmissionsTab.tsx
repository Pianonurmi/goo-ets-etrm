import { Button, Group, Modal, NumberInput, Stack, Text, TextInput } from '@mantine/core';
import { IconCertificate, IconFlame } from '@tabler/icons-react';
import type { ColDef } from 'ag-grid-community';
import dayjs from 'dayjs';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { Can } from '@/shared/auth/Can';
import { notifySuccess } from '@/shared/components/notify';
import { ProblemAlert } from '@/shared/components/ProblemAlert';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDateTime } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import { tonnes, useInvalidateEts } from './EtsPage';
import { RecordEmissionsModal } from './RecordEmissionsModal';
import { type EmissionRecord } from './shared';

export function RecordVerifiedModal({
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

export function EmissionsTab({ installationId, year }: { installationId: string; year: number }) {
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
