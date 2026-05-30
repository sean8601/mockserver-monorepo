import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import MetricsLineChart from '../components/MetricsLineChart';

afterEach(cleanup);

describe('MetricsLineChart', () => {
  it('shows a placeholder when there are fewer than two points', () => {
    const { getByText } = render(<MetricsLineChart series={[{ data: [5], label: 'x' }]} />);
    expect(getByText('collecting…')).toBeInTheDocument();
  });

  it('renders an SVG chart for two or more points (real @mui/x-charts render)', () => {
    const { container } = render(
      <MetricsLineChart series={[{ data: [1, 2, 3], label: 'x' }]} valueFormatter={(v) => `${v}`} />,
    );
    expect(container.querySelector('svg')).not.toBeNull();
  });
});
