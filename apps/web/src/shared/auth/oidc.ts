import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';

export interface OidcSettings {
  authority: string;
  clientId: string;
  scope: string;
}

const CALLBACK_PATH = '/auth/callback';
/** Loaded in a hidden frame by the identity provider when the session is renewed; the server lets the UI frame it. */
export const SILENT_PATH = '/auth/silent';

let manager: UserManager | null = null;
let user: User | null = null;
let redirecting = false;

/** A 401 means the session ended: sign in again and come back here, once however many calls failed together. */
export function reauthenticate(): void {
  if (redirecting) return;
  redirecting = true;
  void manager?.signinRedirect({ state: window.location.pathname + window.location.search });
}

/**
 * Tokens stay in memory only (ADR 0026): a script that reaches the page's storage finds none. A reload signs in
 * again silently in a hidden frame while the identity provider's session lasts, and redirects otherwise. Renewal
 * uses the refresh token when the provider issues one, and the silent frame when it does not.
 */
export function createManager(settings: OidcSettings, origin = window.location.origin): UserManager {
  const created = new UserManager({
    authority: settings.authority,
    client_id: settings.clientId,
    redirect_uri: origin + CALLBACK_PATH,
    silent_redirect_uri: origin + SILENT_PATH,
    post_logout_redirect_uri: origin + '/',
    response_type: 'code',
    scope: settings.scope,
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  });
  created.events.addUserLoaded((loaded) => {
    user = loaded;
  });
  created.events.addUserUnloaded(() => {
    user = null;
  });
  // A renewal that fails (the provider's session ended) leaves an expired token: sign in again before the next call.
  created.events.addSilentRenewError(() => {
    if (!user || user.expired) reauthenticate();
  });
  return created;
}

/**
 * Completes a sign-in redirect or a silent renewal, or makes sure a user is signed in (Authorization Code with PKCE,
 * ADR 0026). Resolves `false` while the browser is being sent to the identity provider, and inside the silent-renew
 * frame, where the application itself must not start.
 */
export async function startOidc(
  settings: OidcSettings,
  created: UserManager = createManager(settings),
): Promise<boolean> {
  manager = created;
  redirecting = false;
  const path = window.location.pathname;
  if (path === SILENT_PATH) {
    await manager.signinSilentCallback();
    return false;
  }
  if (path === CALLBACK_PATH) {
    const signedIn = await manager.signinRedirectCallback();
    user = signedIn;
    const returnTo = typeof signedIn.state === 'string' && signedIn.state.startsWith('/') ? signedIn.state : '/';
    window.history.replaceState(null, '', returnTo);
    return true;
  }
  user = await manager.getUser();
  if (user && !user.expired) return true;
  try {
    user = await manager.signinSilent();
    if (user && !user.expired) return true;
  } catch {
    // No session at the identity provider (or third-party cookies blocked): fall back to the full redirect.
  }
  await manager.signinRedirect({ state: window.location.pathname + window.location.search });
  return false;
}

export function accessToken(): string | null {
  return user && !user.expired ? user.access_token : null;
}

export async function signOut(): Promise<void> {
  await manager?.signoutRedirect();
}
