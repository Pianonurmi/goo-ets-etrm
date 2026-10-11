/**
 * The server-rendered CSV of a new GL export (`ledger_exportCsv`): the export is generated, logged and returned as
 * the file in one call, so the file is exactly what the log's content hash covers.
 */
export function glExportCsvUrl(period: string, legalEntityId: string, format: string | null | undefined): string {
  const query = new URLSearchParams({ legalEntityId });
  if (format) query.set('format', format);
  return `/api/v1/ledger/exports/${encodeURIComponent(period)}/csv?${query.toString()}`;
}

/** The name the file is saved under (the endpoint sends no `Content-Disposition`). */
export function glExportFileName(entityCode: string, period: string, format: string | null | undefined): string {
  const slug = (format ?? 'GENERIC_CSV').toLowerCase().replace(/[^a-z0-9]+/g, '-');
  return `gl-${entityCode.toLowerCase()}-${period}-${slug}.csv`;
}

/**
 * The list of exports leaves the content out, so a logged export can only be saved from the response that created
 * it; a row without content offers no download.
 */
export function hasContent(glExport: { content?: string | null }): boolean {
  return Boolean(glExport.content);
}
