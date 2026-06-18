import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Panel from '../components/Panel';

describe('Panel', () => {
  it('renders title and count badge', () => {
    render(
      <Panel title="Test Panel" count={42} searchValue="" onSearchChange={() => {}}>
        <div>content</div>
      </Panel>,
    );
    expect(screen.getByText('Test Panel')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('renders children', () => {
    render(
      <Panel title="Test" count={0} searchValue="" onSearchChange={() => {}}>
        <div>child content</div>
      </Panel>,
    );
    expect(screen.getByText('child content')).toBeInTheDocument();
  });

  it('renders search input with current value', () => {
    render(
      <Panel title="Test" count={0} searchValue="hello" onSearchChange={() => {}}>
        <div />
      </Panel>,
    );
    const input = screen.getByPlaceholderText('Search...');
    expect(input).toHaveValue('hello');
  });

  it('applies a transition and a hover style to the panel surface for motion', () => {
    const { container } = render(
      <Panel title="Motion" count={1} searchValue="" onSearchChange={() => {}}>
        <div>content</div>
      </Panel>,
    );
    const paper = container.querySelector('.MuiPaper-root') as HTMLElement;
    expect(paper).not.toBeNull();
    // MUI emits the sx transition + :hover rules into emotion <style> tags in the
    // head; assert the panel's generated CSS carries the hover motion.
    const css = Array.from(document.querySelectorAll('style'))
      .map((s) => s.textContent ?? '')
      .join('\n');
    expect(css).toMatch(/transition:[^;]*box-shadow/);
    expect(css).toMatch(/transition:[^;]*border-color/);
    expect(css).toContain(':hover');
  });

  it('calls onSearchChange when typing in search', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();

    render(
      <Panel title="Test" count={0} searchValue="" onSearchChange={onChange}>
        <div />
      </Panel>,
    );

    const input = screen.getByPlaceholderText('Search...');
    await user.type(input, 'abc');

    expect(onChange).toHaveBeenCalledTimes(3);
    expect(onChange).toHaveBeenLastCalledWith('c');
  });
});
