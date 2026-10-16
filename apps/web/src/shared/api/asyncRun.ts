import { api } from './client';
import { ProblemError } from './problem';
import type { Schema } from './types';

export type AsyncRun = Schema<'AsyncRunView'>;

const FIRST_POLL_MS = 250;
const MAX_POLL_MS = 2000;

/** A failed run's problem as the error a request would have raised, for `ProblemAlert` and forms. */
export function runProblem(run: AsyncRun): ProblemError | null {
  if (run.status !== 'FAILED' || !run.problem) return null;
  const { title, status, detail, violations } = run.problem;
  return new ProblemError({
    type: `urn:etrm:problem:${title}`,
    title,
    status,
    detail: detail ?? undefined,
    violations,
  });
}

/**
 * Waits for a long operation that answered 202 (period end, valuation run, report export) and resolves with the id
 * of what it produced; a failed run rejects with its problem, like a failed request. Awaited inside a mutation, so the
 * mutation stays pending for the whole run. Polls `GET /api/v1/runs/{id}` quickly at first, then every two seconds.
 */
export async function awaitRun(run: AsyncRun): Promise<string> {
  let current = run;
  for (let attempt = 0; current.status === 'RUNNING'; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, Math.min(MAX_POLL_MS, FIRST_POLL_MS * 2 ** attempt)));
    const { data } = await api.GET('/api/v1/runs/{id}', { params: { path: { id: current.id } } });
    if (!data) throw new Error(`No status for run ${current.id}`);
    current = data;
  }
  const problem = runProblem(current);
  if (problem) throw problem;
  if (!current.resultId) throw new Error(`Run ${current.id} finished without a result`);
  return current.resultId;
}
