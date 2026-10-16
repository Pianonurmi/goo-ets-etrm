import { http, HttpResponse } from 'msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ORIGIN, server } from '@/test/server';
import { awaitRun, type AsyncRun } from './asyncRun';
import { ProblemError } from './problem';

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const started: AsyncRun = {
  id: 'run-1',
  kind: 'VALUATION',
  status: 'RUNNING',
  requestedBy: 'analyst',
  requestedAt: '2026-10-16T12:00:00Z',
};

describe('awaitRun', () => {
  it('polls the run until it succeeds and resolves with the result id', async () => {
    let polls = 0;
    server.use(
      http.get(`${ORIGIN}/api/v1/runs/run-1`, () => {
        polls += 1;
        return HttpResponse.json(
          polls < 2
            ? started
            : { ...started, status: 'SUCCEEDED', resultId: 'valuation-9', finishedAt: '2026-10-16T12:00:01Z' },
        );
      }),
    );
    await expect(awaitRun(started)).resolves.toBe('valuation-9');
    expect(polls).toBe(2);
  });

  it('does not poll a run that already finished', async () => {
    await expect(awaitRun({ ...started, status: 'SUCCEEDED', resultId: 'export-3' })).resolves.toBe('export-3');
  });

  it('rejects with the problem of a failed run', async () => {
    server.use(
      http.get(`${ORIGIN}/api/v1/runs/run-1`, () =>
        HttpResponse.json({
          ...started,
          status: 'FAILED',
          problem: { status: 422, title: 'validation-failed', detail: 'Period 2026-09 is hard-closed', violations: [] },
        }),
      ),
    );
    const error = await awaitRun(started).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ProblemError);
    expect((error as ProblemError).status).toBe(422);
    expect((error as ProblemError).code).toBe('validation-failed');
    expect((error as ProblemError).message).toBe('Period 2026-09 is hard-closed');
  });

  it('rejects when the run cannot be read', async () => {
    server.use(
      http.get(`${ORIGIN}/api/v1/runs/run-1`, () =>
        HttpResponse.json({ type: 'urn:etrm:problem:not-found', title: 'not-found', status: 404 }, { status: 404 }),
      ),
    );
    await expect(awaitRun(started)).rejects.toBeInstanceOf(ProblemError);
  });
});
