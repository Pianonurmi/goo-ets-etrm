import { describe, expect, it } from 'vitest';
import { glExportCsvUrl, glExportFileName, hasContent } from './glExport';

describe('GL export file', () => {
  it('builds the export call and the file name', () => {
    expect(glExportCsvUrl('2026-09', 'e-1', 'SAP_GL')).toBe(
      '/api/v1/ledger/exports/2026-09/csv?legalEntityId=e-1&format=SAP_GL',
    );
    expect(glExportCsvUrl('2026-09', 'e-1', null)).toBe('/api/v1/ledger/exports/2026-09/csv?legalEntityId=e-1');
    expect(glExportFileName('DEMO-FI', '2026-09', 'SAP_GL')).toBe('gl-demo-fi-2026-09-sap-gl.csv');
    expect(glExportFileName('DEMO-FI', '2026-09', undefined)).toBe('gl-demo-fi-2026-09-generic-csv.csv');
  });

  it('offers a download only where the content came with the row', () => {
    expect(hasContent({ content: '' })).toBe(false);
    expect(hasContent({ content: 'a;b\n' })).toBe(true);
  });
});
