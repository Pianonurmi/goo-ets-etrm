import { Alert, Anchor, Drawer, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { $api } from '@/shared/api/client';
import { FactGrid } from '@/shared/components/DetailLayout';
import { QueryState } from '@/shared/components/QueryState';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal, formatMoney, formatQuantity } from '@/shared/format/format';
import { useCounterparties } from '@/features/trades/hooks';
import { vatRuleLabel } from './model/settlement';

/** One settlement item with its pricing, VAT determination and where it went (invoice), from the items list. */
export function ItemDrawer({ itemId, onClose }: { itemId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const counterparties = useCounterparties();
  const detail = $api.useQuery('get', '/api/v1/settlement/items/{id}', { params: { path: { id: itemId } } });
  const item = detail.data;
  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size="xl"
      title={item ? t('settlement.itemTitle', { trade: item.tradeNumber }) : ''}
      data-testid="item-drawer"
    >
      <QueryState isPending={detail.isPending} error={detail.error}>
        {item && (
          <Stack>
            <Text size="sm" c="dimmed">
              {item.description}
            </Text>
            {item.problem && (
              <Alert color="orange" title={t('settlement.problem')} data-testid="item-problem">
                {item.problem}
              </Alert>
            )}
            <FactGrid
              cols={3}
              facts={[
                {
                  label: t('settlement.trade'),
                  value: (
                    <Anchor component={Link} to={`/trades/${item.tradeId}`} size="sm">
                      {item.tradeNumber}
                    </Anchor>
                  ),
                  testId: 'item-trade',
                },
                {
                  label: t('allocation.status'),
                  value: <StatusBadge status={item.status} data-testid="item-status" />,
                },
                {
                  label: t('trades.columns.counterparty'),
                  value: counterparties.byId.get(item.counterpartyId)?.name ?? '–',
                },
                { label: t('settlement.direction'), value: t(`settlement.directions.${item.direction}`) },
                { label: t('settlement.deliveredOn'), value: formatDate(item.deliveredOn) },
                {
                  label: t('settlement.quantity'),
                  value: formatQuantity(item.quantity, item.unit),
                  testId: 'item-quantity',
                },
                {
                  label: t('settlement.unitPrice', { currency: item.currency, unit: item.unit }),
                  value: item.unitPrice == null ? '–' : formatDecimal(item.unitPrice, 2, 6),
                },
                {
                  label: t('settlement.netAmount'),
                  value: formatMoney(item.netAmount, item.currency),
                  testId: 'item-net',
                },
                {
                  label: t('settlement.vatTreatment'),
                  value: item.vatTreatment ? t(`settlement.treatments.${item.vatTreatment}`) : '–',
                },
                {
                  label: t('settlement.vatRate'),
                  value: item.vatRate == null ? '–' : `${formatDecimal(item.vatRate, 0, 4)} %`,
                },
                { label: t('settlement.vatAmount'), value: formatMoney(item.vatAmount, item.currency) },
                { label: t('settlement.vatRule'), value: vatRuleLabel(item) ?? '–' },
                {
                  label: t('settlement.invoice'),
                  value: item.invoiceId ? (
                    <Anchor component={Link} to={`/settlement/invoices/${item.invoiceId}`} size="sm">
                      {t('settlement.openInvoice')}
                    </Anchor>
                  ) : (
                    '–'
                  ),
                  testId: 'item-invoice',
                },
                { label: t('settlement.itemReference'), value: item.reference ?? '–' },
                { label: t('settlement.version'), value: item.version },
              ]}
            />
          </Stack>
        )}
      </QueryState>
    </Drawer>
  );
}
