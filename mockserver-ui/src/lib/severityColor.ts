/**
 * Maps a severity word to the MUI Chip/Alert colour that represents it.
 *
 * Consolidates the near-identical `severityColor` helpers that previously lived
 * in OptimiseView (HIGH / MEDIUM / LOW signal severity) and DriftPanel
 * (BREAKING / WARNING / INFORMATIONAL semantic severity). The two domains use
 * different severity vocabularies but the same colour intent — the highest tier
 * is `error`, the middle tier is `warning`, the lowest/informational tier is
 * `info`, and anything unrecognised is `default`.
 *
 * Domain-specific mappings that are NOT a severity tier (HTTP status codes,
 * connection state, drift *type*, load-run state) intentionally keep their own
 * local helpers — they are not severities.
 */

/** MUI colour tokens a severity can resolve to. */
export type SeverityColor = 'error' | 'warning' | 'info' | 'default';

/**
 * Resolve a severity string (case-insensitive) to its MUI colour token.
 *
 * Recognised tiers:
 * - `error`: HIGH, CRITICAL, BREAKING
 * - `warning`: MEDIUM, WARNING
 * - `info`: LOW, INFORMATIONAL, INFO
 * - anything else (including `undefined`): `default`
 */
export function severityColor(severity: string | undefined | null): SeverityColor {
  switch ((severity ?? '').toUpperCase()) {
    case 'HIGH':
    case 'CRITICAL':
    case 'BREAKING':
      return 'error';
    case 'MEDIUM':
    case 'WARNING':
      return 'warning';
    case 'LOW':
    case 'INFORMATIONAL':
    case 'INFO':
      return 'info';
    default:
      return 'default';
  }
}
