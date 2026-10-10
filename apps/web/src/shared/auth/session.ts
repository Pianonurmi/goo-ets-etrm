import { configureApiAuth } from '@/shared/api/client';
import type { Schema } from '@/shared/api/types';
import { accessToken, reauthenticate, startOidc } from './oidc';
import { currentPersona, personaHeaders } from './personas';

export type UiConfig = Schema<'UiConfigView'>;

let entityScope: string[] | null = null;

/** Dev personas can be narrowed to some legal entities (sent as `X-Entities`); null means all. */
export function setDevEntityScope(entities: string[] | null): void {
  entityScope = entities;
}

/** Reads the public UI configuration and wires credentials into the API client. Resolves false while redirecting. */
export async function startSession(): Promise<{ config: UiConfig; ready: boolean }> {
  const response = await fetch('/api/v1/ui-config', { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`ui-config answered ${response.status}`);
  const config = (await response.json()) as UiConfig;

  if (config.authMode === 'DEV_HEADERS') {
    configureApiAuth(
      (request) => {
        Object.entries(personaHeaders(currentPersona())).forEach(([name, value]) => {
          request.headers.set(name, value);
        });
        if (entityScope) request.headers.set('X-Entities', entityScope.join(','));
      },
      () => undefined,
    );
    return { config, ready: true };
  }

  if (!config.oidc) throw new Error('ui-config has no OIDC settings');
  const ready = await startOidc(config.oidc);
  configureApiAuth((request) => {
    const token = accessToken();
    if (token) request.headers.set('Authorization', `Bearer ${token}`);
  }, reauthenticate);
  return { config, ready };
}
