import { Group, MultiSelect, NumberInput, Select, TagsInput, Text, TextInput } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { Schema } from '@/shared/api/types';
import type { Criterion, Field } from './model/tree';
import { COUNTRIES, useDeviceOptions, useRegistryOptions, useTaxonomyOptions, type Option } from './useCriteriaData';

type VintageRule = Schema<'VintageRule'>;

/** Options for a field's value source; empty for free text. */
function useValueOptions(field: Field | undefined): Option[] | null {
  const source = field?.valueSource;
  const taxonomy = useTaxonomyOptions(source === 'TAXONOMY' ? field?.taxonomyScheme : null);
  const registries = useRegistryOptions(source === 'REGISTRY');
  const devices = useDeviceOptions(source === 'DEVICE');
  switch (source) {
    case 'ENUM':
    case 'BOOLEAN':
      return (field?.values ?? []).map((value) => ({ value, label: value }));
    case 'COUNTRY':
      return COUNTRIES.map((value) => ({ value, label: value }));
    case 'REGISTRY':
      return registries;
    case 'DEVICE':
      return devices;
    case 'TAXONOMY':
      return taxonomy;
    default:
      return null;
  }
}

function SingleValue({
  field,
  value,
  onChange,
}: {
  field: Field | undefined;
  value: string;
  onChange: (value: string) => void;
}) {
  const { t } = useTranslation();
  const options = useValueOptions(field);
  const label = t('criteria.value');
  return options ? (
    <Select
      aria-label={label}
      placeholder={label}
      data={options}
      value={value || null}
      onChange={(next) => onChange(next ?? '')}
      searchable
      w={260}
    />
  ) : (
    <TextInput
      aria-label={label}
      placeholder={label}
      value={value}
      onChange={(event) => onChange(event.currentTarget.value)}
      w={260}
    />
  );
}

function MultiValue({
  field,
  values,
  onChange,
}: {
  field: Field | undefined;
  values: string[];
  onChange: (values: string[]) => void;
}) {
  const { t } = useTranslation();
  const options = useValueOptions(field);
  const label = t('criteria.values');
  return options ? (
    <MultiSelect
      aria-label={label}
      placeholder={label}
      data={options}
      value={values}
      onChange={onChange}
      searchable
      w={340}
    />
  ) : (
    <TagsInput aria-label={label} placeholder={label} value={values} onChange={onChange} w={340} />
  );
}

function RangeInputs({
  kind,
  low,
  high,
  onChange,
}: {
  kind: 'number' | 'date';
  low: string | number | null | undefined;
  high: string | number | null | undefined;
  onChange: (low: string | number | null, high: string | number | null) => void;
}) {
  const { t } = useTranslation();
  if (kind === 'number') {
    return (
      <Group gap="xs">
        <NumberInput
          aria-label={t('criteria.min')}
          placeholder={t('criteria.min')}
          value={low ?? ''}
          w={130}
          onChange={(v) => onChange(v === '' ? null : v, high ?? null)}
        />
        <NumberInput
          aria-label={t('criteria.max')}
          placeholder={t('criteria.max')}
          value={high ?? ''}
          w={130}
          onChange={(v) => onChange(low ?? null, v === '' ? null : v)}
        />
      </Group>
    );
  }
  return (
    <Group gap="xs">
      <TextInput
        type="date"
        aria-label={t('criteria.from')}
        value={(low as string | null) ?? ''}
        w={160}
        onChange={(e) => onChange(e.currentTarget.value || null, high ?? null)}
      />
      <TextInput
        type="date"
        aria-label={t('criteria.toExclusive')}
        value={(high as string | null) ?? ''}
        w={160}
        onChange={(e) => onChange(low ?? null, e.currentTarget.value || null)}
      />
    </Group>
  );
}

function VintageEditor({ rule, onChange }: { rule: VintageRule; onChange: (rule: VintageRule) => void }) {
  const { t } = useTranslation();
  const year = new Date().getFullYear();
  const defaults: Record<VintageRule['type'], VintageRule> = {
    productionYear: { type: 'productionYear', year },
    sameCalendarYearAsDelivery: { type: 'sameCalendarYearAsDelivery' },
    monthsBeforeDelivery: { type: 'monthsBeforeDelivery', months: 12 },
    explicit: { type: 'explicit', from: `${year}-01-01`, toExclusive: `${year + 1}-01-01` },
  };
  return (
    <Group gap="xs">
      <Select
        aria-label={t('criteria.vintageRule')}
        data={Object.keys(defaults).map((type) => ({ value: type, label: t(`criteria.vintage.${type}`) }))}
        value={rule.type}
        onChange={(type) => type && onChange(defaults[type as VintageRule['type']])}
        allowDeselect={false}
        w={230}
      />
      {rule.type === 'productionYear' && (
        <NumberInput
          aria-label={t('criteria.year')}
          value={rule.year}
          w={100}
          onChange={(v) => onChange({ ...rule, year: Number(v) })}
        />
      )}
      {rule.type === 'monthsBeforeDelivery' && (
        <NumberInput
          aria-label={t('criteria.months')}
          value={rule.months}
          min={1}
          w={100}
          onChange={(v) => onChange({ ...rule, months: Number(v) })}
        />
      )}
      {rule.type === 'explicit' && (
        <RangeInputs
          kind="date"
          low={rule.from}
          high={rule.toExclusive}
          onChange={(from, to) => onChange({ ...rule, from: String(from ?? ''), toExclusive: String(to ?? '') })}
        />
      )}
    </Group>
  );
}

/** The inputs of one condition; combinators are handled by the builder. */
export function ConditionEditor({
  node,
  fields,
  onChange,
}: {
  node: Criterion;
  fields: Map<string, Field>;
  onChange: (node: Criterion) => void;
}) {
  const { t } = useTranslation();
  const regions = useTaxonomyOptions(node.type === 'countryInRegion' ? 'REGION' : null);
  const labels = useTaxonomyOptions(node.type === 'labelsAny' || node.type === 'labelsAll' ? 'LABEL' : null);
  const field = 'attribute' in node ? fields.get(node.attribute) : undefined;
  const title = field?.label ?? t(`criteria.types.${node.type}`);
  const operator = t(`criteria.operators.${node.type}`);

  let inputs: React.ReactNode;
  switch (node.type) {
    case 'equals':
      inputs = <SingleValue field={field} value={node.value} onChange={(value) => onChange({ ...node, value })} />;
      break;
    case 'in':
      inputs = <MultiValue field={field} values={node.values} onChange={(values) => onChange({ ...node, values })} />;
      break;
    case 'descendantOf':
      inputs = <SingleValue field={field} value={node.code} onChange={(code) => onChange({ ...node, code })} />;
      break;
    case 'numberRange':
      inputs = (
        <RangeInputs
          kind="number"
          low={node.min}
          high={node.max}
          onChange={(min, max) =>
            onChange({ ...node, min: min === null ? null : Number(min), max: max === null ? null : Number(max) })
          }
        />
      );
      break;
    case 'dateRange':
      inputs = (
        <RangeInputs
          kind="date"
          low={node.from}
          high={node.toExclusive}
          onChange={(from, to) => onChange({ ...node, from: from as string | null, toExclusive: to as string | null })}
        />
      );
      break;
    case 'labelsAny':
    case 'labelsAll':
      inputs = (
        <MultiSelect
          aria-label={t('criteria.labels')}
          data={labels}
          value={node.labels}
          onChange={(values) => onChange({ ...node, labels: values })}
          searchable
          w={340}
        />
      );
      break;
    case 'countryInRegion':
      inputs = (
        <Select
          aria-label={t('criteria.region')}
          placeholder={t('criteria.region')}
          data={regions}
          value={node.region || null}
          onChange={(region) => onChange({ ...node, region: region ?? '' })}
          searchable
          w={260}
        />
      );
      break;
    case 'deviceAgeAtDelivery':
      inputs = (
        <RangeInputs
          kind="number"
          low={node.minYears ?? 0}
          high={node.maxYears}
          onChange={(min, max) => onChange({ ...node, minYears: Number(min ?? 0), maxYears: Number(max ?? 0) })}
        />
      );
      break;
    case 'vintage':
      inputs = <VintageEditor rule={node.rule} onChange={(rule) => onChange({ ...node, rule })} />;
      break;
    default:
      inputs = null;
  }
  return (
    <Group gap="sm" wrap="wrap" align="center">
      <Text size="sm" fw={600} miw={150}>
        {title}
      </Text>
      <Text size="sm" c="dimmed">
        {operator}
      </Text>
      {inputs}
    </Group>
  );
}
