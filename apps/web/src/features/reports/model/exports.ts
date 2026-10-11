import type { Schema } from '@/shared/api/types';
import type { ReportDefinition } from './parameters';

export type ReportExport = Schema<'ReportExportView'>;
export type EmirEvent = Schema<'EmirEventView'>;

/**
 * A logged export can be downloaded again by whoever may run its report (the service checks the report's roles and
 * the export's entity), so the runnable reports decide.
 */
export function canDownloadExport(
  reportExport: Pick<ReportExport, 'reportCode'>,
  runnable: readonly Pick<ReportDefinition, 'code'>[],
): boolean {
  return runnable.some((report) => report.code === reportExport.reportCode);
}

/** The fallback name of a downloaded export (a CSV export comes with its own name from the server). */
export function exportFileName(reportExport: Pick<ReportExport, 'reportCode' | 'seq' | 'format'>): string {
  return `${reportExport.reportCode.toLowerCase()}-${reportExport.seq}.${reportExport.format === 'CSV' ? 'csv' : 'json'}`;
}

/**
 * The events that offer "report an error": the latest lifecycle event of each trade shown, unless the trade was
 * already reported in error. The service also requires a reported new trade (NEWT), possibly outside the dates shown,
 * and says so when it is missing.
 */
export function errorReportableEvents(events: readonly EmirEvent[]): Set<string> {
  const errored = new Set(events.filter((event) => event.action === 'EROR').map((event) => event.tradeId));
  const latest = new Map<string, EmirEvent>();
  for (const event of events) {
    if (event.action === 'VALU' || errored.has(event.tradeId)) continue;
    const previous = latest.get(event.tradeId);
    if (!previous || previous.recordedAt < event.recordedAt) latest.set(event.tradeId, event);
  }
  return new Set([...latest.values()].map((event) => event.id));
}
