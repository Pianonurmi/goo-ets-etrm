import { afterEach, beforeEach, vi } from 'vitest';
import { download, fileNameFrom, REVOKE_DELAY_MS } from './download';

describe('fileNameFrom', () => {
  it('reads plain and encoded file names', () => {
    expect(fileNameFrom('attachment; filename="gl-export-7.csv"', 'x.csv')).toBe('gl-export-7.csv');
    expect(fileNameFrom("attachment; filename*=UTF-8''r%C3%A4port.csv", 'x.csv')).toBe('räport.csv');
    expect(fileNameFrom(null, 'fallback.csv')).toBe('fallback.csv');
  });
});

describe('download', () => {
  let createObjectUrl = vi.fn(() => 'blob:test');
  let revokeObjectUrl = vi.fn();
  let click = vi.fn();

  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response('a,b\n1,2\n', { headers: { 'Content-Disposition': 'attachment; filename="x.csv"' } }),
        ),
      ),
    );
    createObjectUrl = vi.fn(() => 'blob:test');
    revokeObjectUrl = vi.fn();
    click = vi.fn();
    URL.createObjectURL = createObjectUrl;
    URL.revokeObjectURL = revokeObjectUrl;
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(click);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('releases the object URL only after the download had time to start', async () => {
    await download('/api/v1/x.csv', 'fallback.csv');
    expect(click).toHaveBeenCalledTimes(1);
    expect(createObjectUrl).toHaveBeenCalledTimes(1);
    expect(revokeObjectUrl).not.toHaveBeenCalled();
    vi.advanceTimersByTime(REVOKE_DELAY_MS);
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:test');
  });
});
