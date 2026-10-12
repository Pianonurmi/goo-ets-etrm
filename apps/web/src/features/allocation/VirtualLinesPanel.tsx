import { Alert, Badge, Group, Paper, Stack, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { Schema } from '@/shared/api/types';
import { StatusBadge } from '@/shared/components/StatusBadge';
import { formatDate, formatDecimal } from '@/shared/format/format';
import { groupVirtualLines, hasFutureCoverage, obligationName } from './model/plan';

type Obligation = Schema<'ObligationView'>;
type VirtualLine = Schema<'VirtualLineView'>;
type Coverage = Schema<'CoverageReport'>;

/** Per obligation: demand, covered by lots, and (when the plan looked at future supply) covered with purchases and expected issuance. */
export function FutureCoverageTable({
  coverage,
  obligations,
  testId,
}: {
  coverage: Coverage;
  obligations: Map<string, Obligation>;
  testId: string;
}) {
  const { t } = useTranslation();
  const future = hasFutureCoverage(coverage);
  return (
    <Paper withBorder radius="lg" p="md" data-testid={testId}>
      <Table striped withRowBorders={false}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('allocation.obligation')}</Table.Th>
            <Table.Th ta="right">{t('allocation.demand')}</Table.Th>
            <Table.Th ta="right">{t('allocation.covered')}</Table.Th>
            {future && <Table.Th ta="right">{t('allocation.virtual.withPurchases')}</Table.Th>}
            {future && <Table.Th ta="right">{t('allocation.virtual.withExpected')}</Table.Th>}
            <Table.Th ta="right">
              {t(future ? 'allocation.virtual.shortOfHoldings' : 'allocation.shortfallLabel')}
            </Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody className="etrm-figure">
          {coverage.obligations.map((row) => (
            <Table.Tr key={row.obligationId}>
              <Table.Td>{obligationName(obligations.get(row.obligationId)) ?? row.obligationId.slice(-8)}</Table.Td>
              <Table.Td ta="right">{formatDecimal(row.demandMwh)}</Table.Td>
              <Table.Td ta="right">{formatDecimal(row.coveredMwh)}</Table.Td>
              {future && (
                <Table.Td ta="right">
                  {row.coveredWithContractedMwh == null ? '—' : formatDecimal(row.coveredWithContractedMwh)}
                </Table.Td>
              )}
              {future && (
                <Table.Td ta="right">
                  {row.coveredWithExpectedMwh == null ? '—' : formatDecimal(row.coveredWithExpectedMwh)}
                </Table.Td>
              )}
              <Table.Td ta="right">{formatDecimal(row.shortfallMwh)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Paper>
  );
}

/**
 * The part of a plan that no certificate serves yet: purchases agreed but not delivered and issuance expected from
 * production devices. Nothing here is reserved or instructed; it shows what would cover the obligation and when.
 */
export function VirtualLinesPanel({
  lines,
  coverage,
  obligations,
  testId,
}: {
  lines: VirtualLine[];
  coverage: Coverage | null | undefined;
  obligations: Map<string, Obligation>;
  testId: string;
}) {
  const { t } = useTranslation();
  const inEur = lines.length > 0 && lines.every((line) => line.breakdown.unit === 'EUR_PER_MWH');
  const withPremium = lines.some((line) => line.breakdown.reliabilityPremium != null);
  return (
    <Stack gap="sm" data-testid={testId}>
      <Alert color="gray" variant="light" data-testid={`${testId}-note`}>
        {t('allocation.virtual.note')}
      </Alert>
      {coverage && hasFutureCoverage(coverage) && (
        <FutureCoverageTable coverage={coverage} obligations={obligations} testId={`${testId}-coverage`} />
      )}
      {groupVirtualLines(lines).map((group) => {
        const obligation = obligations.get(group.obligationId);
        return (
          <Paper withBorder radius="lg" p="md" key={group.obligationId}>
            <Group justify="space-between" mb="xs">
              <Text fw={600} size="sm">
                {obligation?.criteriaSummary ?? group.obligationId.slice(-8)}
              </Text>
              <Group gap="xs">
                {obligation && (
                  <Badge variant="default" radius="xl" tt="none">
                    {t('allocation.due', { date: formatDate(obligation.deliveryDate) })}
                  </Badge>
                )}
                <Badge variant="light" radius="xl" tt="none">
                  {formatDecimal(group.totalMwh)} MWh
                </Badge>
              </Group>
            </Group>
            <Table striped withRowBorders={false}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t('allocation.virtual.source')}</Table.Th>
                  <Table.Th ta="right">MWh</Table.Th>
                  <Table.Th>{t('positions.future.availableFrom')}</Table.Th>
                  <Table.Th ta="right">{t(inEur ? 'allocation.costPerMwh' : 'allocation.score')}</Table.Th>
                  {withPremium && <Table.Th ta="right">{t('allocation.virtual.reliabilityPremium')}</Table.Th>}
                  <Table.Th>{t('allocation.status')}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody className="etrm-figure">
                {group.lines.map((line) => (
                  <Table.Tr key={line.id} data-testid={`virtual-line-${line.kind}`}>
                    <Table.Td>
                      <Text size="sm">{line.description}</Text>
                      <Text size="xs" c="dimmed" data-testid="virtual-line-kind">
                        {t(`positions.future.kinds.${line.kind}`)}
                      </Text>
                    </Table.Td>
                    <Table.Td ta="right">{formatDecimal(line.quantityMwh)}</Table.Td>
                    <Table.Td style={{ whiteSpace: 'nowrap' }}>{formatDate(line.availableFrom)}</Table.Td>
                    <Table.Td ta="right">{formatDecimal(line.score, 2, 4)}</Table.Td>
                    {withPremium && (
                      <Table.Td ta="right">
                        {line.breakdown.reliabilityPremium == null
                          ? '—'
                          : formatDecimal(line.breakdown.reliabilityPremium, 2, 2)}
                      </Table.Td>
                    )}
                    <Table.Td style={{ whiteSpace: 'nowrap', minWidth: 120 }}>
                      <StatusBadge status={line.status} data-testid="virtual-line-status" />
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Paper>
        );
      })}
    </Stack>
  );
}
