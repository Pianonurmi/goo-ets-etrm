import type { Role, Schema } from '@/shared/api/types';

export type ReportDefinition = Schema<'ReportDefinitionView'>;
export type ReportParameter = Schema<'ReportParameter'>;
export type ParameterInput = 'entity' | 'date' | 'month' | 'int' | 'text';

/** How a parameter is entered: the legal entity comes from the header, the rest from typed inputs. */
export function parameterInput(parameter: Pick<ReportParameter, 'name' | 'type'>): ParameterInput {
  if (parameter.name === 'legalEntityId') return 'entity';
  switch (parameter.type) {
    case 'date':
      return 'date';
    case 'yyyy-MM':
      return 'month';
    case 'int':
      return 'int';
    default:
      return 'text';
  }
}

/** The reports a user may run: those naming one of the user's roles (ADMIN runs everything). */
export function runnableReports(catalogue: ReportDefinition[], roles: readonly Role[]): ReportDefinition[] {
  if (roles.includes('ADMIN')) return catalogue;
  return catalogue.filter((report) => report.roles.some((role) => roles.includes(role)));
}

/** Required parameters still missing a value (the entity is filled in from the header). */
export function missingParameters(
  definition: ReportDefinition,
  values: Record<string, string>,
  entityId: string | undefined,
): string[] {
  return definition.parameters
    .filter((p) => p.required)
    .filter((p) => !(parameterInput(p) === 'entity' ? entityId : values[p.name]?.trim()))
    .map((p) => p.name);
}

/** The query string of a report run: one plain parameter per value (the API reads them as a map). */
export function reportQuery(
  definition: ReportDefinition,
  values: Record<string, string>,
  entityId: string | undefined,
  format: 'json' | 'csv',
): string {
  const query = new URLSearchParams({ format });
  for (const parameter of definition.parameters) {
    const value = parameterInput(parameter) === 'entity' ? entityId : values[parameter.name]?.trim();
    if (value) query.set(parameter.name, value);
  }
  return `/api/v1/reports/${encodeURIComponent(definition.code)}?${query.toString()}`;
}
