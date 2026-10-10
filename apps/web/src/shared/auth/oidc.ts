import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';

export interface OidcSettings {
  authority: string;
  clientId: string;
  scope: string;
}

const CALLBACK_PATH = '/auth/callback';

let manager: UserManager | null = null;
let user: User | null = null;

function createManager(settings: OidcSettings): UserManager {
  const origin = window.location.origin;
  const created = new UserManager({
    authority: settings.authority,
    client_id: settings.clientId,
    redirect_uri: origin + CALLBACK_PATH,
    post_logout_redirect_uri: origin + '/',
    response_type: 'code',
    scope: settings.scope,
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
  created.events.addUserLoaded((loaded) => {
    user = loaded;
  });
  created.events.addUserUnloaded(() => {
    user = null;
  });
  return created;
}

/**
 * Completes a sign-in redirect or makes sure a user is signed in (Authorization Code with PKCE, ADR 0026).
 * Resolves `false` while the browser is being sent to the identity provider.
 */
export async function startOidc(settings: OidcSettings): Promise<boolean> {
  manager = createManager(settings);
  if (window.location.pathname === CALLBACK_PATH) {
    const signedIn = await manager.signinRedirectCallback();
    user = signedIn;
    const returnTo = typeof signedIn.state === 'string' && signedIn.state.startsWith('/') ? signedIn.state : '/';
    window.history.replaceState(null, '', returnTo);
    return true;
  }
  user = await manager.getUser();
  if (user && !user.expired) return true;
  await manager.signinRedirect({ state: window.location.pathname + window.location.search });
  return false;
}

export function accessToken(): string | null {
  return user && !user.expired ? user.access_token : null;
}

export async function signOut(): Promise<void> {
  await manager?.signoutRedirect();
}

/** A 401 means the session ended: sign in again and come back here. */
export function reauthenticate(): void {
  void manager?.signinRedirect({ state: window.location.pathname + window.location.search });
}
