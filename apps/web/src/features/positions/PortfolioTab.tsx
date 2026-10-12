import { ActionIcon, Alert, Badge, Group, Stack, Text, Tooltip } from '@mantine/core';
import { IconChevronDown, IconChevronRight } from '@tabler/icons-react';
import type { ColDef, ICellRendererParams } from 'ag-grid-community';
import { useCallback, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { token } from '@/app/tokens';
import { $api } from '@/shared/api/client';
import { EmptyState } from '@/shared/components/EmptyState';
import { QueryState } from '@/shared/components/QueryState';
import { TableSkeleton } from '@/shared/components/Skeletons';
import { formatDateTime, formatDecimal } from '@/shared/format/format';
import { DataGrid } from '@/shared/grid/DataGrid';
import {
  flattenPortfolio,
  toggleExpanded,
  type ExpandState,
  type PortfolioRow,
  type PortfolioView,
} from './model/portfolio';

const INDENT_PX = 20;
const mwh = (p: { value: unknown }) => formatDecimal(p.value as number);
const euro = (p: { value: unknown }) => (p.value == null ? '—' : formatDecimal(p.value as number, 2, 2));
const figure = 'etrm-figure';

/** The product cell: indent by level, a toggle when the node has children, name and code, and where a repeat is from. */
function ProductCell({ row, onToggle }: { row: PortfolioRow; onToggle: (row: PortfolioRow) => void }) {
  const { t } = useTranslation();
  const { node } = row;
  return (
    <Group gap={6} wrap="nowrap" pl={row.level * INDENT_PX} h="100%" data-testid={`portfolio-row-${node.bucketCode}`}>
      {row.hasChildren ? (
        <ActionIcon
          size="xs"
          variant="subtle"
          color="gray"
          aria-expanded={row.expanded}
          aria-label={t(row.expanded ? 'positions.portfolio.collapse' : 'positions.portfolio.expand', {
            name: node.name,
          })}
          data-testid={`portfolio-toggle-${node.bucketCode}`}
          onClick={() => onToggle(row)}
        >
          {row.expanded ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
        </ActionIcon>
      ) : (
        <span style={{ display: 'inline-block', width: 22, flexShrink: 0 }} />
      )}
      <Text component="span" size="sm" truncate fw={row.level === 0 ? 600 : undefined}>
        {node.name}
      </Text>
      <Text component="span" size="xs" c="dimmed" ff="monospace" truncate>
        {node.bucketCode}
      </Text>
      {row.alsoUnder && (
        <Tooltip label={t('positions.portfolio.alsoUnderHint', { name: row.alsoUnder.name })} multiline w={260}>
          <Badge size="xs" variant="light" color="brand" style={{ flexShrink: 0 }}>
            {t('positions.portfolio.alsoUnderBadge')}
          </Badge>
        </Tooltip>
      )}
    </Group>
  );
}

/** The grid of the portfolio: an indented tree of buckets, built from the server's figures without any arithmetic. */
export function PortfolioGrid({ view }: { view: PortfolioView }) {
  const { t } = useTranslation();
  const [state, setState] = useState<ExpandState>({});
  const rows = useMemo(() => flattenPortfolio(view.nodes, state), [view.nodes, state]);
  const onToggle = useCallback(
    (row: PortfolioRow) => setState((current) => toggleExpanded(current, row.key, row.level)),
    [],
  );
  const columns = useMemo<ColDef<PortfolioRow>[]>(() => {
    const quantity = (
      colId: string,
      headerName: string,
      get: (row: PortfolioRow) => number | null | undefined,
      extra: Partial<ColDef<PortfolioRow>> = {},
    ): ColDef<PortfolioRow> => ({
      colId,
      headerName,
      width: 130,
      type: 'rightAligned',
      valueGetter: (p) => (p.data ? get(p.data) : undefined),
      valueFormatter: mwh,
      cellClass: figure,
      ...extra,
    });
    return [
      {
        colId: 'product',
        headerName: t('positions.portfolio.product'),
        flex: 1,
        minWidth: 400,
        pinned: 'left',
        valueGetter: (p) => (p.data ? `${p.data.node.name} ${p.data.node.bucketCode}` : ''),
        tooltipValueGetter: (p) => {
          const aliases = p.data?.node.aliases ?? [];
          return aliases.length > 0 ? t('positions.portfolio.aliases', { aliases: aliases.join(', ') }) : '';
        },
        cellRenderer: (p: ICellRendererParams<PortfolioRow>) =>
          p.data && <ProductCell row={p.data} onToggle={onToggle} />,
      },
      quantity('long', t('positions.portfolio.long'), (r) => r.node.longMwh, {
        headerTooltip: t('positions.portfolio.longHint'),
      }),
      quantity('short', t('positions.portfolio.short'), (r) => r.node.shortMwh, {
        headerTooltip: t('positions.portfolio.shortHint'),
      }),
      quantity('coveredWithin', t('positions.portfolio.coveredWithin'), (r) => r.node.coveredWithinMwh, {
        headerTooltip: t('positions.portfolio.coveredWithinHint'),
        width: 150,
      }),
      quantity('shortAfterNetting', t('positions.portfolio.shortAfterNetting'), (r) => r.node.shortAfterNettingMwh, {
        headerTooltip: t('positions.portfolio.shortAfterNettingHint'),
        width: 160,
        cellRenderer: (p: ICellRendererParams<PortfolioRow>) =>
          p.data && (
            <span
              style={p.data.node.shortAfterNettingMwh > 0 ? { color: token('warning'), fontWeight: 600 } : undefined}
              data-warning={p.data.node.shortAfterNettingMwh > 0 ? 'true' : undefined}
            >
              {formatDecimal(p.data.node.shortAfterNettingMwh)}
            </span>
          ),
      }),
      quantity('naiveNet', t('positions.portfolio.naiveNet'), (r) => r.node.naiveNetMwh, {
        headerTooltip: t('positions.portfolio.naiveNetHint'),
        tooltipValueGetter: (p) =>
          p.data && p.data.node.naiveNetMwh >= 0 && p.data.node.shortAfterNettingMwh > 0
            ? t('positions.portfolio.naiveNetMisleading')
            : t('positions.portfolio.naiveNetHint'),
      }),
      quantity('free', t('positions.portfolio.free'), (r) => r.node.freeMwh, {
        headerTooltip: t('positions.portfolio.freeHint'),
      }),
      quantity('availableToSell', t('positions.portfolio.availableToSell'), (r) => r.node.availableToSellMwh, {
        headerTooltip: t('positions.portfolio.availableToSellHint'),
        width: 150,
      }),
      quantity('mark', t('positions.portfolio.mark', { currency: view.currency }), (r) => r.node.markEur, {
        valueFormatter: euro,
        headerTooltip: t('positions.portfolio.markHint'),
      }),
      quantity(
        'cheapestToDeliver',
        t('positions.portfolio.cheapestToDeliver', { currency: view.currency }),
        (r) => r.node.cheapestToDeliverEur,
        { valueFormatter: euro, headerTooltip: t('positions.portfolio.cheapestToDeliverHint'), width: 150 },
      ),
      quantity(
        'giveAway',
        t('positions.portfolio.giveAway', { currency: view.currency }),
        (r) => r.node.giveAwayPerMwhEur,
        { valueFormatter: euro, headerTooltip: t('positions.portfolio.giveAwayHint'), width: 140 },
      ),
    ];
  }, [t, onToggle, view.currency]);
  return (
    <div data-testid="goo-hierarchy">
      <DataGrid<PortfolioRow>
        rowData={rows}
        columnDefs={columns}
        defaultColDef={{ sortable: false, filter: false, suppressMovable: true }}
        rowId={(row) => row.key}
        fitRows={16}
        tooltipShowDelay={400}
        overlayNoRowsTemplate={t('common.empty')}
      />
    </div>
  );
}

/** The GoO portfolio by product: what is held, owed and free in each bucket and in the buckets that contain it. */
export function PortfolioTab({ entityId }: { entityId: string }) {
  const { t } = useTranslation();
  // The first call for an entity prepares the figures, so it can take a moment.
  const portfolio = $api.useQuery(
    'get',
    '/api/v1/allocation/portfolio',
    { params: { query: { holderEntityId: entityId } } },
    { retry: false },
  );
  const data = portfolio.data;
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        {t('positions.portfolio.lead')}
      </Text>
      <Alert color="gray" variant="light" data-testid="portfolio-note">
        {t('positions.portfolio.note')}
      </Alert>
      <QueryState isPending={false} error={portfolio.error}>
        {!data ? (
          <TableSkeleton rows={6} />
        ) : data.nodes.length === 0 ? (
          <EmptyState title={t('positions.portfolio.empty')} description={t('positions.portfolio.emptyHint')} />
        ) : (
          <>
            <PortfolioGrid view={data} />
            <Text size="xs" c="dimmed">
              {t('positions.portfolio.computedAt', { time: formatDateTime(data.computedAt) })}
            </Text>
          </>
        )}
      </QueryState>
    </Stack>
  );
}
