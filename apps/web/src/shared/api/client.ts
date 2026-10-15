import createFetchClient, { type Middleware } from 'openapi-fetch';
import createQueryClient from 'openapi-react-query';
import { edgeSignInError, isEdgeRedirect } from './edge';
import { problemFromResponse } from './problem';
import type { paths } from './schema';

/** Adds credentials to every request: a bearer token (OIDC) or the dev persona headers. */
export type RequestAuthenticator = (request: Request) => Promise<void> | void;

let authenticate: RequestAuthenticator = () => undefined;
let onUnauthorized: () => void = () => undefined;
let traceId: string | null = null;

/** The `X-Trace-Id` of the last API response, so an error report can name the request that led to it. */
export function lastTraceId(): string | null {
  return traceId;
}

export function configureApiAuth(authenticator: RequestAuthenticator, unauthorized: () => void): void {
  authenticate = authenticator;
  onUnauthorized = unauthorized;
}

const auth: Middleware = {
  async onRequest({ request }) {
    await authenticate(request);
    return request;
  },
  async onResponse({ response }) {
    traceId = response.headers.get('X-Trace-Id') ?? traceId;
    if (response.ok) return response;
    if (isEdgeRedirect(response)) throw edgeSignInError();
    if (response.status === 401) onUnauthorized();
    throw await problemFromResponse(response);
  },
};

/** Typed fetch client for the API; failures throw `ProblemError`. */
export const api = createFetchClient<paths>({
  baseUrl: window.location.origin,
  // Resolved per call, so test doubles installed later are picked up. Redirects are not followed (`edge.ts`).
  fetch: (request) => globalThis.fetch(request, { redirect: 'manual' }),
});
api.use(auth);

/** TanStack Query bindings: `$api.useQuery('get', '/api/v1/me')`. */
export const $api = createQueryClient(api);

/** Plain fetch with the same credentials, for downloads and other non-JSON calls. */
export async function authorisedFetch(input: string, init?: RequestInit): Promise<Response> {
  const request = new Request(new URL(input, window.location.origin), init);
  await authenticate(request);
  const response = await fetch(request, { redirect: 'manual' });
  traceId = response.headers.get('X-Trace-Id') ?? traceId;
  if (!response.ok) {
    if (isEdgeRedirect(response)) throw edgeSignInError();
    if (response.status === 401) onUnauthorized();
    throw await problemFromResponse(response);
  }
  return response;
}
