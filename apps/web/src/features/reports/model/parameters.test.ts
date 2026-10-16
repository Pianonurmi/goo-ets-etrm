import { describe, expect, it } from 'vitest';
import {
  missingParameters,
  parameterInput,
  reportParameters,
  runnableReports,
  type ReportDefinition,
} from './parameters';

const report: ReportDefinition = {
  code: 'TRIAL_BALANCE',
  title: 'Trial balance',
  description: '',
  roles: ['ACCOUNTANT', 'AUDITOR'],
  parameters: [
    { name: 'legalEntityId', type: 'uuid', required: true, description: '' },
    { name: 'period', type: 'yyyy-MM', required: true, description: '' },
    { name: 'year', type: 'int', required: false, description: '' },
  ],
};

describe('report parameters', () => {
  it('chooses inputs by name and type', () => {
    expect(report.parameters.map(parameterInput)).toEqual(['entity', 'month', 'int']);
    expect(parameterInput({ name: 'from', type: 'date' })).toBe('date');
    expect(parameterInput({ name: 'x', type: 'string' })).toBe('text');
  });

  it('offers reports by role', () => {
    expect(runnableReports([report], ['TRADER'])).toEqual([]);
    expect(runnableReports([report], ['AUDITOR'])).toEqual([report]);
    expect(runnableReports([report], ['ADMIN'])).toEqual([report]);
  });

  it('lists what is missing and builds the query', () => {
    expect(missingParameters(report, {}, 'e-1')).toEqual(['period']);
    expect(missingParameters(report, { period: '2026-09' }, undefined)).toEqual(['legalEntityId']);
    expect(reportParameters(report, { period: ' 2026-09 ', year: '' }, 'e-1')).toEqual({
      legalEntityId: 'e-1',
      period: '2026-09',
    });
  });
});
