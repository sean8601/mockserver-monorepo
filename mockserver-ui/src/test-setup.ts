import '@testing-library/jest-dom/vitest';

// jsdom does not implement ResizeObserver, which @mui/x-charts (and other
// responsive components) rely on. Provide a no-op so charts can render in tests.
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class ResizeObserverStub {
    observe(): void {}
    unobserve(): void {}
    disconnect(): void {}
  } as unknown as typeof ResizeObserver;
}
