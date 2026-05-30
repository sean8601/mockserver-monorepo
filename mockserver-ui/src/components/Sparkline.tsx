import { useTheme } from '@mui/material/styles';

interface SparklineProps {
  data: number[];
  width?: number;
  height?: number;
  color?: string;
  ariaLabel?: string;
}

/**
 * Tiny inline-SVG sparkline — no charting dependency. Normalises `data` to its
 * own min/max and draws a polyline. Renders an empty box for <2 points.
 */
export default function Sparkline({ data, width = 140, height = 36, color, ariaLabel }: SparklineProps) {
  const theme = useTheme();
  const stroke = color ?? theme.palette.primary.main;

  const finite = data.filter((v) => Number.isFinite(v));
  if (finite.length < 2) {
    return <svg width={width} height={height} role="img" aria-label={ariaLabel} />;
  }

  const max = Math.max(...finite);
  const min = Math.min(...finite);
  const range = max - min || 1;
  const stepX = width / (finite.length - 1);
  const points = finite
    .map((v, i) => {
      const x = i * stepX;
      const y = height - ((v - min) / range) * height;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');

  return (
    <svg
      width={width}
      height={height}
      role="img"
      aria-label={ariaLabel}
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
    >
      <polyline points={points} fill="none" stroke={stroke} strokeWidth={1.5} strokeLinejoin="round" />
    </svg>
  );
}
