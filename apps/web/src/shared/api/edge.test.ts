import { afterEach, describe, expect, it, vi } from 'vitest';
import { edgeSignInError, isEdgeRedirect } from './edge';

describe('edge sign-in', () => {
  afterEach(() => {
    window.sessionStorage.clear();
    vi.useRealTimers();
  });

  it('reloads once, then not again within a minute', () => {
    vi.useFakeTimers({ now: new Date('2026-10-13T10:00:00Z') });
    const reload = vi.fn();
    expect(edgeSignInError(reload).code).toBe('edge-sign-in');
    edgeSignInError(reload);
    expect(reload).toHaveBeenCalledTimes(1);
    vi.setSystemTime(new Date('2026-10-13T10:01:01Z'));
    edgeSignInError(reload);
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it('recognises only an opaque redirect', () => {
    expect(isEdgeRedirect({ type: 'opaqueredirect' } as Response)).toBe(true);
    expect(isEdgeRedirect(new Response(null, { status: 401 }))).toBe(false);
  });
});
