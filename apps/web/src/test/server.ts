import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { Role, Schema } from '@/shared/api/types';

export const ORIGIN = window.location.origin;

export const entities: Schema<'LegalEntityView'>[] = [
  {
    id: 'e1',
    code: 'DEMO-FI',
    name: 'Demo Energia Oy',
    country: 'FI',
    baseCurrency: 'EUR',
    timeZone: 'Europe/Helsinki',
    active: true,
    version: 1,
    accountingPolicy: 'IFRS',
    validPeriod: { start: '2026-01-01' },
  } as unknown as Schema<'LegalEntityView'>,
];

/** The API as seen by the shell: me echoes the dev headers it receives. */
export const server = setupServer(
  http.get(`${ORIGIN}/api/v1/me`, ({ request }) => {
    const roles = (request.headers.get('X-Roles') ?? '').split(',').filter(Boolean) as Role[];
    const userId = request.headers.get('X-Actor') ?? 'system';
    return HttpResponse.json({
      userId,
      displayName: userId,
      roles,
      legalEntityIds: [],
      allEntities: true,
      authMode: 'DEV_HEADERS',
    } satisfies Schema<'MeView'>);
  }),
  http.get(`${ORIGIN}/api/v1/reference/legal-entities`, () => HttpResponse.json(entities)),
  http.get(`${ORIGIN}/api/v1/workflow/task-types`, () => HttpResponse.json([])),
  http.get(`${ORIGIN}/api/v1/workflow/tasks/counts`, () => HttpResponse.json({ TRADE_APPROVAL: 2 })),
  http.get(`${ORIGIN}/api/v1/workflow/tasks`, () => HttpResponse.json({ items: [], nextCursor: null })),
);
