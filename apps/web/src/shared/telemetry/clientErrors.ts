import { api, lastTraceId } from '../api/client';
import type { Body } from '../api/types';

export type ClientErrorReport = Body<'ClientErrorReport'>;
export type ClientErrorKind = ClientErrorReport['kind'];

/** A browser sends at most this many reports a minute and this many in all; the server limits per user too. */
const PER_MINUTE = 5;
const PER_PAGE_LOAD = 50;
const TRACE_ID = /^[0-9a-f]{16,32}$/;

function truncate(text: string, max: number): string {
  return text.length > max ? text.slice(0, max) : text;
}

function describe(error: unknown): { message: string; stack?: string } {
  if (error instanceof Error) return { message: error.message || error.name, stack: error.stack };
  if (typeof error === 'string') return { message: error };
  try {
    return { message: JSON.stringify(error) ?? String(error) };
  } catch {
    return { message: String(error) };
  }
}

/**
 * Turns uncaught errors into reports for `/api/v1/client-errors`. The same error is reported once per page load
 * (a render loop would otherwise repeat it), and the browser keeps to its own limits, so a broken screen cannot
 * flood the server. Sending never throws: a report that fails is dropped.
 */
export function createClientErrorReporter(
  send: (report: ClientErrorReport) => Promise<unknown>,
  now: () => number = Date.now,
): (kind: ClientErrorKind, error: unknown) => boolean {
  const seen = new Set<string>();
  let windowStart = Number.NEGATIVE_INFINITY;
  let inWindow = 0;
  return (kind, error) => {
    const { message, stack } = describe(error);
    // Errors from scripts of another origin carry nothing but this text.
    if (message === 'Script error.' && !stack) return false;
    const key = `${kind}|${message}|${stack?.split('\n', 2)[1] ?? ''}`;
    if (seen.has(key)) return false;
    const time = now();
    if (time - windowStart >= 60_000) {
      windowStart = time;
      inWindow = 0;
    }
    if (inWindow >= PER_MINUTE || seen.size >= PER_PAGE_LOAD) return false;
    seen.add(key);
    inWindow += 1;
    const traceId = lastTraceId();
    send({
      kind,
      message: truncate(message.trim() || 'Unknown error', 1000),
      stack: stack ? truncate(stack, 8000) : undefined,
      page: truncate(window.location.pathname + window.location.search, 500),
      lastTraceId: traceId && TRACE_ID.test(traceId) ? traceId : undefined,
    }).catch(() => undefined);
    return true;
  };
}

const report = createClientErrorReporter((body) => api.POST('/api/v1/client-errors', { body }));

/** Reports an error a component caught but could not recover from (the router's error page). */
export function reportClientError(kind: ClientErrorKind, error: unknown): void {
  report(kind, error);
}

/** Listens for uncaught errors and unhandled rejections; returns the function that stops listening. */
export function installClientErrorReporting(target: Window = window): () => void {
  const onError = (event: ErrorEvent) => report('ERROR', event.error ?? event.message);
  const onRejection = (event: PromiseRejectionEvent) => report('UNHANDLED_REJECTION', event.reason);
  target.addEventListener('error', onError);
  target.addEventListener('unhandledrejection', onRejection);
  return () => {
    target.removeEventListener('error', onError);
    target.removeEventListener('unhandledrejection', onRejection);
  };
}
