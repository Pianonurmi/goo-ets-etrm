import { asProblemError, isProblem, problemFromResponse, ProblemError } from './problem';

describe('problems', () => {
  it('reads a problem body and exposes its code', async () => {
    const response = new Response(
      JSON.stringify({
        type: 'urn:etrm:problem:segregation-of-duties',
        title: 'segregation-of-duties',
        status: 403,
        detail: 'You requested this approval',
      }),
      { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
    );
    const error = await problemFromResponse(response);
    expect(error).toBeInstanceOf(ProblemError);
    expect(error.code).toBe('segregation-of-duties');
    expect(error.status).toBe(403);
    expect(error.message).toBe('You requested this approval');
  });

  it('turns a body that is not a problem into a generic one', async () => {
    const error = await problemFromResponse(
      new Response('<html>gateway</html>', { status: 502, statusText: 'Bad Gateway' }),
    );
    expect(error.status).toBe(502);
    expect(error.code).toBe('http-502');
    expect(error.message).toBe('Bad Gateway');
  });

  it('wraps network failures', () => {
    const error = asProblemError(new TypeError('Failed to fetch'));
    expect(error.status).toBe(0);
    expect(error.message).toBe('Failed to fetch');
    expect(asProblemError(error)).toBe(error);
  });

  it('recognises problems', () => {
    expect(isProblem({ type: 'x', status: 400, title: 'x' })).toBe(true);
    expect(isProblem({ message: 'nope' })).toBe(false);
    expect(isProblem(null)).toBe(false);
  });
});
