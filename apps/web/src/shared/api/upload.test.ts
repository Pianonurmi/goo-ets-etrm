import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ORIGIN, server } from '@/test/server';
import { ProblemError } from './problem';
import { uploadText } from './upload';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('uploadText', () => {
  it('posts the text with its media type and the query, and returns the JSON answer', async () => {
    let seen: { type: string | null; body: string; url: string } | null = null;
    server.use(
      http.post(`${ORIGIN}/api/v1/settlement/payments/csv`, async ({ request }) => {
        seen = { type: request.headers.get('Content-Type'), body: await request.text(), url: request.url };
        return HttpResponse.json([{ id: 'p1' }], { status: 201 });
      }),
    );
    const result = await uploadText<{ id: string }[]>(
      '/api/v1/settlement/payments/csv',
      { legalEntityId: 'e1', skip: undefined },
      'BANK-1,2026-10-01,100.00,EUR\n',
    );
    expect(result).toEqual([{ id: 'p1' }]);
    expect(seen).toEqual({
      type: 'text/csv; charset=utf-8',
      body: 'BANK-1,2026-10-01,100.00,EUR\n',
      url: `${ORIGIN}/api/v1/settlement/payments/csv?legalEntityId=e1`,
    });
  });

  it('throws the problem the server answers', async () => {
    server.use(
      http.post(`${ORIGIN}/api/v1/settlement/payments/csv`, () =>
        HttpResponse.json({ status: 422, title: 'validation-failed', detail: 'Line 1: bad amount' }, { status: 422 }),
      ),
    );
    await expect(uploadText('/api/v1/settlement/payments/csv', {}, 'x')).rejects.toBeInstanceOf(ProblemError);
  });
});
