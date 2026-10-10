import { useMemo } from 'react';
import { $api } from '@/shared/api/client';
import type { paths } from '@/shared/api/schema';
import type { Catalogue, Field } from './model/tree';

/** Countries a GoO device is commonly in (EU, EEA, Switzerland, UK and the Western Balkans); free text is accepted too. */
export const COUNTRIES = [
  'AT',
  'BA',
  'BE',
  'BG',
  'CH',
  'CY',
  'CZ',
  'DE',
  'DK',
  'EE',
  'ES',
  'FI',
  'FR',
  'GB',
  'GR',
  'HR',
  'HU',
  'IE',
  'IS',
  'IT',
  'LT',
  'LU',
  'LV',
  'ME',
  'MK',
  'MT',
  'NL',
  'NO',
  'PL',
  'PT',
  'RO',
  'RS',
  'SE',
  'SI',
  'SK',
];

type TaxonomyScheme = paths['/api/v1/taxonomy/{scheme}']['get']['parameters']['path']['scheme'];

export interface Option {
  value: string;
  label: string;
}

export function useCatalogue(): { catalogue: Catalogue | undefined; fields: Map<string, Field> } {
  const { data } = $api.useQuery('get', '/api/v1/products/criteria/catalogue', {}, { staleTime: Infinity });
  return useMemo(
    () => ({ catalogue: data, fields: new Map((data?.fields ?? []).map((field) => [field.attribute, field])) }),
    [data],
  );
}

/** Taxonomy codes of a scheme, indented by depth so the hierarchy shows in a select. */
export function useTaxonomyOptions(scheme: string | null | undefined): Option[] {
  const { data } = $api.useQuery(
    'get',
    '/api/v1/taxonomy/{scheme}',
    { params: { path: { scheme: (scheme ?? 'REGION') as TaxonomyScheme } } },
    { enabled: Boolean(scheme), staleTime: 5 * 60_000 },
  );
  return useMemo(
    () =>
      (data ?? [])
        .slice()
        .sort((a, b) => a.path.localeCompare(b.path))
        .map((node) => ({
          value: node.code,
          label: `${'  '.repeat(Math.max(0, node.depth - 1))}${node.name} (${node.code})`,
        })),
    [data],
  );
}

export function useRegistryOptions(enabled: boolean): Option[] {
  const { data } = $api.useQuery('get', '/api/v1/reference/registries', {}, { enabled, staleTime: 5 * 60_000 });
  return useMemo(
    () => (data ?? []).map((registry) => ({ value: registry.code, label: `${registry.name} (${registry.code})` })),
    [data],
  );
}

export function useDeviceOptions(enabled: boolean): Option[] {
  const { data } = $api.useQuery('get', '/api/v1/reference/production-devices', {}, { enabled, staleTime: 60_000 });
  return useMemo(
    () => (data ?? []).map((device) => ({ value: device.gsrn, label: `${device.name} (${device.gsrn})` })),
    [data],
  );
}
