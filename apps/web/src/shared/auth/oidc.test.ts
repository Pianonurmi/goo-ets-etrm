import type { User, UserManager } from 'oidc-client-ts';
import { accessToken, createManager, reauthenticate, SILENT_PATH, startOidc } from './oidc';

const settings = { authority: 'https://idp.example/realms/etrm', clientId: 'etrm-web', scope: 'openid' };

function fakeUser(token: string, expired = false): User {
  return { access_token: token, expired, state: undefined } as unknown as User;
}

function fakeManager(signinSilent = vi.fn(() => Promise.resolve(fakeUser('silent')))) {
  const mocks = {
    signinSilentCallback: vi.fn(() => Promise.resolve()),
    signinRedirectCallback: vi.fn(() =>
      Promise.resolve({ access_token: 'from-callback', expired: false, state: '/trades?x=1' } as unknown as User),
    ),
    getUser: vi.fn(() => Promise.resolve(null)),
    signinSilent,
    signinRedirect: vi.fn(() => Promise.resolve()),
  };
  return { manager: mocks as unknown as UserManager, ...mocks };
}

function at(path: string) {
  window.history.replaceState(null, '', path);
}

describe('OIDC sign-in (ADR 0026)', () => {
  it('keeps tokens out of browser storage and renews through the silent page', () => {
    const settingsOf = createManager(settings, 'https://etrm.example').settings;
    expect(settingsOf.silent_redirect_uri).toBe('https://etrm.example' + SILENT_PATH);
    expect(settingsOf.automaticSilentRenew).toBe(true);
    createManager(settings, 'https://etrm.example');
    expect(Object.keys(window.sessionStorage).filter((key) => key.startsWith('oidc.user'))).toEqual([]);
  });

  it('completes the silent renewal inside its frame without starting the application', async () => {
    at(SILENT_PATH);
    const { manager, signinSilentCallback, signinRedirect } = fakeManager();
    await expect(startOidc(settings, manager)).resolves.toBe(false);
    expect(signinSilentCallback).toHaveBeenCalledOnce();
    expect(signinRedirect).not.toHaveBeenCalled();
  });

  it('completes a sign-in redirect and returns to the page it came from', async () => {
    at('/auth/callback?code=c&state=s');
    const { manager } = fakeManager();
    await expect(startOidc(settings, manager)).resolves.toBe(true);
    expect(window.location.pathname + window.location.search).toBe('/trades?x=1');
    expect(accessToken()).toBe('from-callback');
  });

  it('signs in silently after a reload, and redirects only when that fails', async () => {
    at('/inbox');
    const silent = fakeManager();
    await expect(startOidc(settings, silent.manager)).resolves.toBe(true);
    expect(accessToken()).toBe('silent');
    expect(silent.signinRedirect).not.toHaveBeenCalled();

    const noSession = fakeManager(vi.fn(() => Promise.reject(new Error('login_required'))));
    await expect(startOidc(settings, noSession.manager)).resolves.toBe(false);
    expect(noSession.signinRedirect).toHaveBeenCalledWith({ state: '/inbox' });
  });

  it('starts one redirect however many calls fail with 401 together', async () => {
    at('/inbox');
    const { manager, signinRedirect } = fakeManager();
    await startOidc(settings, manager);
    reauthenticate();
    reauthenticate();
    reauthenticate();
    expect(signinRedirect).toHaveBeenCalledOnce();
  });
});
