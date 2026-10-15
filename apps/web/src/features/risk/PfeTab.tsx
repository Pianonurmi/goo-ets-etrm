import { Stack, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { $api } from '@/shared/api/client';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDecimal } from '@/shared/format/format';

export function PfeTab() {
  const { t } = useTranslation();
  const addOns = $api.useQuery('get', '/api/v1/risk/pfe-add-ons');
  return (
    <QueryState isPending={addOns.isPending} error={addOns.error}>
      <Stack>
        <Text size="sm" c="dimmed">
          {t('risk.pfeHint')}
        </Text>
        <Table withTableBorder striped maw={640}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('risk.productFamily')}</Table.Th>
              <Table.Th ta="right">{t('risk.maxTenor')}</Table.Th>
              <Table.Th ta="right">{t('risk.addOnPct')}</Table.Th>
              <Table.Th>{t('risk.calibrated')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(addOns.data ?? []).map((row) => (
              <Table.Tr key={`${row.productFamily}-${row.maxTenorMonths}`}>
                <Table.Td>{t(`trades.families.${row.productFamily}`)}</Table.Td>
                <Table.Td ta="right">{row.maxTenorMonths >= 9999 ? t('risk.longer') : row.maxTenorMonths}</Table.Td>
                <Table.Td ta="right">{formatDecimal(row.pct, 0, 2)} %</Table.Td>
                <Table.Td>
                  {row.calibrated ? t('common.yes') : <StatusBadge status="WARNING" label={t('risk.notCalibrated')} />}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Stack>
    </QueryState>
  );
}
