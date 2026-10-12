/** RFC 9457 problem as answered by the API (`ProblemDetailsHandler`). */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  violations?: { field: string; message: string }[];
}

/** Every failed API call surfaces as a ProblemError, whether the server sent a problem or not. */
export class ProblemError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title);
    this.name = 'ProblemError';
    this.problem = problem;
  }

  get status(): number {
    return this.problem.status;
  }

  /** The stable code after `urn:etrm:problem:`, e.g. `validation-failed` or `segregation-of-duties`. */
  get code(): string {
    return this.problem.type.replace(/^urn:etrm:problem:/, '');
  }
}

export function isProblem(value: unknown): value is Problem {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as Problem).status === 'number' &&
    typeof (value as Problem).type === 'string'
  );
}

/** Builds the error for a non-2xx response; a body that is not a problem becomes a generic one. */
export async function problemFromResponse(response: Response): Promise<ProblemError> {
  let body: unknown;
  try {
    body = await response.clone().json();
  } catch {
    body = undefined;
  }
  if (isProblem(body)) return new ProblemError(body);
  return new ProblemError({
    type: `urn:etrm:problem:http-${response.status}`,
    title: response.statusText || `HTTP ${response.status}`,
    status: response.status,
    detail: response.statusText || undefined,
  });
}

export function asProblemError(error: unknown): ProblemError {
  if (error instanceof ProblemError) return error;
  const message = error instanceof Error ? error.message : String(error);
  return new ProblemError({ type: 'urn:etrm:problem:network', title: 'network', status: 0, detail: message });
}
