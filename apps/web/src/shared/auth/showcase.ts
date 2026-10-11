import { problemFromResponse } from '@/shared/api/problem';
import { currentPersona } from './personas';

/** The showcase (ADR 0032): a demo sign-in with a session cookie, then any persona of the catalogue. */
export const PERSONA_HEADER = 'X-Persona';
const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const EXPIRED_KEY = 'etrm.sessionExpired';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

/** The CSRF token Spring Security keeps in a readable cookie; echoed in a header on every unsafe request. */
export function csrfToken(cookies: string = document.cookie): string | null {
  const entry = cookies
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${CSRF_COOKIE}=`));
  return entry ? decodeURIComponent(entry.slice(CSRF_COOKIE.length + 1)) : null;
}

/** Credentials for a showcase API call: the persona, and the CSRF token on unsafe methods. */
export function showcaseHeaders(method: string, personaId: string, token: string | null): Record<string, string> {
  const headers: Record<string, string> = { [PERSONA_HEADER]: personaId };
  if (!SAFE_METHODS.has(method.toUpperCase()) && token) headers[CSRF_HEADER] = token;
  return headers;
}

export function authenticateShowcase(request: Request): void {
  Object.entries(showcaseHeaders(request.method, currentPersona().id, csrfToken())).forEach(([name, value]) => {
    request.headers.set(name, value);
  });
}

/** The session ended (timeout or a reset): back to the sign-in page, which says why. */
export function showcaseSessionExpired(): void {
  try {
    window.sessionStorage.setItem(EXPIRED_KEY, '1');
  } catch {
    // Without storage the sign-in page simply does not explain why it is shown.
  }
  window.location.reload();
}

/** True once after the session expired, so the sign-in page can say so. */
export function takeSessionExpired(): boolean {
  try {
    const expired = window.sessionStorage.getItem(EXPIRED_KEY) === '1';
    window.sessionStorage.removeItem(EXPIRED_KEY);
    return expired;
  } catch {
    return false;
  }
}

async function post(path: string, body?: URLSearchParams): Promise<Response> {
  const token = csrfToken();
  return fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...(token ? { [CSRF_HEADER]: token } : {}) },
    body,
  });
}

/** Signs in with a demo account; failures (wrong password, too many attempts) throw `ProblemError`. */
export async function signInShowcase(username: string, password: string): Promise<void> {
  const response = await post('/api/v1/auth/login', new URLSearchParams({ username, password }));
  if (!response.ok) throw await problemFromResponse(response);
}

export async function signOutShowcase(): Promise<void> {
  await post('/api/v1/auth/logout');
  window.location.assign('/');
}
