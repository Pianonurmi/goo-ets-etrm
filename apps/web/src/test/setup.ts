import '@testing-library/jest-dom/vitest';
import '@/app/i18n';

// Tests format as en-GB whatever the machine's locale is; format.test.ts overrides it where the locale matters.
Object.defineProperty(window.navigator, 'language', { configurable: true, get: () => 'en-GB' });

// Mantine reads matchMedia and ResizeObserver, which jsdom does not provide.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
window.ResizeObserver = ResizeObserverStub;
