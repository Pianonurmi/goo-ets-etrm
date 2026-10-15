import { createClientErrorReporter, installClientErrorReporting, type ClientErrorReport } from './clientErrors';

describe('createClientErrorReporter', () => {
  function reporter() {
    const sent: ClientErrorReport[] = [];
    let time = 0;
    const report = createClientErrorReporter(
      (body) => {
        sent.push(body);
        return Promise.resolve();
      },
      () => time,
    );
    return { sent, report, advance: (ms: number) => (time += ms) };
  }

  it('sends the message, stack and page of an error', () => {
    const { sent, report } = reporter();
    const error = new Error('boom');
    expect(report('ERROR', error)).toBe(true);
    expect(sent).toHaveLength(1);
    expect(sent[0]).toMatchObject({ kind: 'ERROR', message: 'boom', page: window.location.pathname });
    expect(sent[0]?.stack).toContain('boom');
  });

  it('reports the same error once, and describes values that are not errors', () => {
    const { sent, report } = reporter();
    const error = new Error('again');
    report('ERROR', error);
    expect(report('ERROR', error)).toBe(false);
    report('UNHANDLED_REJECTION', { code: 7 });
    expect(sent.map((body) => body.message)).toEqual(['again', '{"code":7}']);
  });

  it('keeps to five reports a minute and ignores errors of other origins', () => {
    const { sent, report, advance } = reporter();
    for (let i = 0; i < 7; i += 1) report('ERROR', new Error(`e${i}`));
    expect(sent).toHaveLength(5);
    expect(report('ERROR', 'Script error.')).toBe(false);
    advance(60_000);
    expect(report('ERROR', new Error('later'))).toBe(true);
  });

  it('does not throw when sending fails', async () => {
    const report = createClientErrorReporter(() => Promise.reject(new Error('offline')));
    expect(report('ERROR', new Error('x'))).toBe(true);
    await Promise.resolve();
  });
});

describe('installClientErrorReporting', () => {
  it('listens to errors and rejections until stopped', () => {
    const target = new EventTarget() as unknown as Window;
    const added = vi.spyOn(target, 'addEventListener');
    const removed = vi.spyOn(target, 'removeEventListener');
    const stop = installClientErrorReporting(target);
    expect(added.mock.calls.map(([type]) => type)).toEqual(['error', 'unhandledrejection']);
    stop();
    expect(removed.mock.calls.map(([type]) => type)).toEqual(['error', 'unhandledrejection']);
  });
});
