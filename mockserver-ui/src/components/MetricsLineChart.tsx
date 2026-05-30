import { LineChart } from '@mui/x-charts/LineChart';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';

export interface MetricsSeries {
  data: number[];
  label: string;
}

interface MetricsLineChartProps {
  series: MetricsSeries[];
  height?: number;
  /** Formats y-axis + tooltip values (e.g. bytes → "1.2 MB"). */
  valueFormatter?: (value: number) => string;
}

/**
 * Thin wrapper around `@mui/x-charts` LineChart for the Metrics view: hides the
 * (index-based) x labels, disables point marks for a clean live line, and shows
 * a "collecting…" placeholder until at least two samples exist.
 */
export default function MetricsLineChart({ series, height = 220, valueFormatter }: MetricsLineChartProps) {
  // shortest series length, so an accidental ragged input shows the placeholder
  // rather than silently clipping points.
  const length = series.length === 0 ? 0 : Math.min(...series.map((s) => s.data.length));

  if (length < 2) {
    return (
      <Box sx={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Typography variant="caption" color="text.secondary">
          collecting…
        </Typography>
      </Box>
    );
  }

  const xData = Array.from({ length }, (_, i) => i);

  return (
    <LineChart
      height={height}
      series={series.map((s) => ({
        data: s.data,
        label: s.label,
        showMark: false,
        curve: 'linear' as const,
        valueFormatter: valueFormatter ? (v: number | null) => (v == null ? '' : valueFormatter(v)) : undefined,
      }))}
      xAxis={[{ data: xData, scaleType: 'point', valueFormatter: () => '' }]}
      yAxis={[{ valueFormatter: valueFormatter ? (v: number) => valueFormatter(v) : undefined }]}
      margin={{ left: 56, right: 12, top: 16, bottom: 8 }}
      hideLegend={series.length <= 1}
    />
  );
}
