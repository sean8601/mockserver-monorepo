import { describe, it, expect, afterEach } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import VirtualList from '../components/VirtualList';
import { PanelScrollContext } from '../components/PanelScrollContext';

// A real (non-windowing) scroll element can't be laid out in jsdom — clientHeight
// and getBoundingClientRect both report 0 — so by default VirtualList takes its
// render-all fallback (which is what every other panel test exercises). To prove
// the windowing path, this harness mocks a measurable viewport on the scroll
// element and a ResizeObserver that fires synchronously.

const VIEWPORT = 400;

class FiringResizeObserver {
  private readonly cb: ResizeObserverCallback;
  constructor(cb: ResizeObserverCallback) {
    this.cb = cb;
  }
  observe(el: Element): void {
    this.cb(
      [
        {
          target: el,
          contentRect: { height: VIEWPORT, width: 300 },
          // @tanstack/virtual-core reads borderBoxSize first, falling back to
          // offsetWidth/offsetHeight — both mocked below.
          borderBoxSize: [{ blockSize: VIEWPORT, inlineSize: 300 }],
        } as unknown as ResizeObserverEntry,
      ],
      this as unknown as ResizeObserver,
    );
  }
  unobserve(): void {}
  disconnect(): void {}
}

function mockViewport(el: HTMLElement): void {
  Object.defineProperty(el, 'clientHeight', { value: VIEWPORT, configurable: true });
  Object.defineProperty(el, 'offsetHeight', { value: VIEWPORT, configurable: true });
  Object.defineProperty(el, 'offsetWidth', { value: 300, configurable: true });
  el.getBoundingClientRect = () =>
    ({ height: VIEWPORT, width: 300, top: 0, left: 0, right: 300, bottom: VIEWPORT, x: 0, y: 0, toJSON() {} }) as DOMRect;
}

function WindowedHarness({ count }: { count: number }) {
  const [el, setEl] = useState<HTMLDivElement | null>(null);
  return (
    <div
      ref={(node) => {
        if (node && !el) {
          mockViewport(node);
          setEl(node);
        }
      }}
    >
      <PanelScrollContext.Provider value={el}>
        <VirtualList
          count={count}
          getKey={(i) => `row-${i}`}
          estimateSize={20}
          renderRow={(i) => <div data-testid="row">row {i}</div>}
        />
      </PanelScrollContext.Provider>
    </div>
  );
}

describe('VirtualList', () => {
  const originalRO = globalThis.ResizeObserver;
  afterEach(() => {
    globalThis.ResizeObserver = originalRO;
  });

  it('renders every row on the fallback path (no measurable viewport)', () => {
    render(
      <VirtualList
        count={50}
        getKey={(i) => `row-${i}`}
        renderRow={(i) => <div data-testid="row">row {i}</div>}
      />,
    );
    expect(screen.getAllByTestId('row')).toHaveLength(50);
  });

  it('windows the list when the viewport is measurable, rendering far fewer than all rows', () => {
    globalThis.ResizeObserver = FiringResizeObserver as unknown as typeof ResizeObserver;
    render(<WindowedHarness count={500} />);

    const rendered = screen.getAllByTestId('row');
    // A 400px viewport with ~20px rows plus overscan mounts a few dozen rows,
    // nowhere near all 500 — that gap is the whole point of windowing.
    expect(rendered.length).toBeGreaterThan(0);
    expect(rendered.length).toBeLessThan(100);
    // The first row is in the initial window.
    expect(screen.getByText('row 0')).toBeInTheDocument();
  });
});
