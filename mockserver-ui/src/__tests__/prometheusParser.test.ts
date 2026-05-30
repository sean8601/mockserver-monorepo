import { describe, it, expect } from 'vitest';
import {
  parsePrometheusText,
  findSample,
  metricValue,
} from '../lib/prometheusParser';

// A faithful slice of a real MockServer `GET /mockserver/metrics` response.
const SAMPLE = `# HELP requests_received_count Number of requests received
# TYPE requests_received_count gauge
requests_received_count 13.0
expectations_not_matched_count 5.0
response_expectations_matched_count 5.0
response_actions_count 1.0
websocket_callback_clients_count 0.0
mock_server_build_info{artifact_id="mockserver-core",group_id="org.mock-server",major_minor_version="6.1",version="6.1.0"} 1.0
`;

describe('parsePrometheusText', () => {
  it('parses unlabelled gauge samples', () => {
    const samples = parsePrometheusText(SAMPLE);
    expect(metricValue(samples, 'requests_received_count')).toBe(13);
    expect(metricValue(samples, 'expectations_not_matched_count')).toBe(5);
    expect(metricValue(samples, 'response_expectations_matched_count')).toBe(5);
  });

  it('skips HELP/TYPE comments and blank lines', () => {
    const samples = parsePrometheusText(SAMPLE);
    // 6 data lines, no comment lines leaked in
    expect(samples).toHaveLength(6);
    expect(samples.every((s) => !s.name.startsWith('#'))).toBe(true);
  });

  it('parses labels on build_info', () => {
    const samples = parsePrometheusText(SAMPLE);
    const buildInfo = findSample(samples, 'mock_server_build_info');
    expect(buildInfo).toBeDefined();
    expect(buildInfo?.value).toBe(1);
    expect(buildInfo?.labels.version).toBe('6.1.0');
    expect(buildInfo?.labels.artifact_id).toBe('mockserver-core');
  });

  it('returns the fallback for an absent metric', () => {
    const samples = parsePrometheusText(SAMPLE);
    expect(metricValue(samples, 'does_not_exist')).toBe(0);
    expect(metricValue(samples, 'does_not_exist', -1)).toBe(-1);
  });

  it('handles +Inf / -Inf / NaN values', () => {
    const samples = parsePrometheusText('a_inf +Inf\nb_ninf -Inf\nc_nan NaN\n');
    expect(metricValue(samples, 'a_inf')).toBe(Number.POSITIVE_INFINITY);
    expect(metricValue(samples, 'b_ninf')).toBe(Number.NEGATIVE_INFINITY);
    expect(Number.isNaN(metricValue(samples, 'c_nan'))).toBe(true);
  });

  it('ignores an optional trailing timestamp', () => {
    const samples = parsePrometheusText('with_ts 42.0 1700000000000\n');
    expect(metricValue(samples, 'with_ts')).toBe(42);
  });

  it('unescapes quoted label values', () => {
    const samples = parsePrometheusText('m{k="a\\"b\\\\c"} 1.0\n');
    expect(findSample(samples, 'm')?.labels.k).toBe('a"b\\c');
  });

  it('returns an empty array for empty input', () => {
    expect(parsePrometheusText('')).toEqual([]);
    expect(parsePrometheusText('\n\n# just a comment\n')).toEqual([]);
  });
});
