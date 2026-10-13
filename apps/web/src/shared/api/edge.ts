import { ProblemError } from './problem';

const RELOADED_AT = 'etrm.edgeReloadAt';
const QUIET_MS = 60_000;

/**
 * A redirect in answer to an API call comes from an access proxy in front of the application (Cloudflare Access, see
 * docs/deploy/vps.md) whose session has expired: the API itself never redirects. Calls are made with
 * `redirect: 'manual'`, so the browser hands over an opaque redirect instead of following it into a CORS failure, and
 * the page reloads once so the proxy can sign the user in again. A second redirect within a minute does not reload.
 */
export function edgeSignInError(reload: () => void = () => window.location.reload()): ProblemError {
  let recent = false;
  try {
    const at = Number(window.sessionStorage.getItem(RELOADED_AT) ?? 0);
    recent = Date.now() - at < QUIET_MS;
    if (!recent) window.sessionStorage.setItem(RELOADED_AT, String(Date.now()));
  } catch {
    // Storage blocked: reload anyway, the proxy's sign-in page ends any loop.
  }
  if (!recent) reload();
  return new ProblemError({ type: 'urn:etrm:problem:edge-sign-in', title: 'edge-sign-in', status: 0 });
}

export function isEdgeRedirect(response: Response): boolean {
  return response.type === 'opaqueredirect';
}
