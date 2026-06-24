import { describe, it, expect } from 'vitest';
import { severityColor } from '../lib/severityColor';

describe('severityColor', () => {
  it('maps the high tier (HIGH / CRITICAL / BREAKING) to error', () => {
    expect(severityColor('HIGH')).toBe('error');
    expect(severityColor('CRITICAL')).toBe('error');
    expect(severityColor('BREAKING')).toBe('error');
  });

  it('maps the middle tier (MEDIUM / WARNING) to warning', () => {
    expect(severityColor('MEDIUM')).toBe('warning');
    expect(severityColor('WARNING')).toBe('warning');
  });

  it('maps the low tier (LOW / INFORMATIONAL / INFO) to info', () => {
    expect(severityColor('LOW')).toBe('info');
    expect(severityColor('INFORMATIONAL')).toBe('info');
    expect(severityColor('INFO')).toBe('info');
  });

  it('is case-insensitive', () => {
    expect(severityColor('high')).toBe('error');
    expect(severityColor('Warning')).toBe('warning');
    expect(severityColor('informational')).toBe('info');
  });

  it('returns default for unknown, empty, null and undefined', () => {
    expect(severityColor('SOMETHING_ELSE')).toBe('default');
    expect(severityColor('')).toBe('default');
    expect(severityColor(undefined)).toBe('default');
    expect(severityColor(null)).toBe('default');
  });
});
