import { authorisedFetch } from './client';

/**
 * Posts text such as CSV to an endpoint that declares `text/csv` (bank statements, registry statements,
 * counterparty confirmations) and returns its JSON answer. Failures throw `ProblemError` like any other call.
 */
export async function uploadText<T>(
  path: string,
  query: Record<string, string | undefined>,
  text: string,
  contentType = 'text/csv',
): Promise<T> {
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== '') params.set(key, value);
  });
  const search = params.toString();
  const response = await authorisedFetch(search ? `${path}?${search}` : path, {
    method: 'POST',
    headers: { 'Content-Type': `${contentType}; charset=utf-8` },
    body: text,
  });
  return (await response.json()) as T;
}
