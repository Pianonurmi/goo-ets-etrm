import { Button } from '@mantine/core';
import { IconHistory, IconPencil } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useTranslation } from 'react-i18next';
import type { Body, Schema } from '@/shared/api/types';
import { StatusBadge } from '@/shared/components/StatusBadge';

export type LegalEntity = Schema<'LegalEntityView'>;

export type Counterparty = Schema<'CounterpartyView'>;

export type Registry = Schema<'RegistryView'>;

export type Device = Schema<'ProductionDeviceView'>;

export type AccountType = Body<'CreateRegistryAccount'>['accountType'];

export type BookType = Body<'CreateBook'>['bookType'];

export type CounterpartyType = NonNullable<Body<'CreateCounterparty'>['counterpartyType']>;

export const ACCOUNT_TYPES: AccountType[] = [
  'HOLDING',
  'PRODUCTION',
  'CANCELLATION_ONLY',
  'OPERATOR_HOLDING',
  'AIRCRAFT_OPERATOR_HOLDING',
  'TRADING',
  'PERSON_HOLDING',
];

export const BOOK_TYPES: BookType[] = ['TRADING', 'OWN_USE', 'HEDGING', 'COMPLIANCE'];

export const COUNTERPARTY_TYPES: CounterpartyType[] = [
  'EXTERNAL',
  'INTERCOMPANY',
  'BROKER',
  'EXCHANGE',
  'CLEARING_HOUSE',
];

export function ActiveBadge({ active }: { active: boolean }) {
  const { t } = useTranslation();
  return (
    <StatusBadge status={active ? 'ACTIVE' : 'INACTIVE'} label={active ? t('admin.active') : t('admin.inactive')} />
  );
}

/** The active column of the lists, as a badge. */
export function activeColumn<Row extends { active: boolean }>(headerName: string): ColDef<Row> {
  return {
    field: 'active' as ColDef<Row>['field'],
    headerName,
    width: 110,
    cellRenderer: (p: ICellRendererParams<Row>) => (p.data ? <ActiveBadge active={p.data.active} /> : null),
  };
}

export function EditButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation();
  return (
    <Button
      size="compact-sm"
      variant="subtle"
      leftSection={<IconPencil size={14} />}
      onClick={onClick}
      data-testid="record-edit"
    >
      {t('admin.edit')}
    </Button>
  );
}

export function HistoryButton({ onClick }: { onClick: () => void }) {
  const { t } = useTranslation();
  return (
    <Button
      size="compact-sm"
      variant="subtle"
      leftSection={<IconHistory size={14} />}
      onClick={onClick}
      data-testid="record-history"
    >
      {t('admin.history')}
    </Button>
  );
}
