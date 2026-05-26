import { useMemo } from 'react';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import { useDashboardStore } from '../store';
import { parseTraffic, type ParsedTraffic } from '../lib/llmTraffic';
import { estimateCostUsd } from '../lib/llmPricing';

// ---------------------------------------------------------------------------
// Token extraction per provider kind
// ---------------------------------------------------------------------------

interface TokenCounts {
  inputTokens: number;
  outputTokens: number;
}

function extractTokens(parsed: ParsedTraffic): TokenCounts | null {
  if (parsed.kind === 'anthropic' && parsed.usage) {
    return {
      inputTokens: parsed.usage.input_tokens ?? 0,
      outputTokens: parsed.usage.output_tokens ?? 0,
    };
  }
  if ((parsed.kind === 'openai' || parsed.kind === 'openai_responses') && parsed.usage) {
    return {
      inputTokens: parsed.usage.prompt_tokens ?? 0,
      outputTokens: parsed.usage.completion_tokens ?? 0,
    };
  }
  if (parsed.kind === 'gemini' && parsed.usage) {
    return {
      inputTokens: parsed.usage.promptTokenCount ?? 0,
      outputTokens: parsed.usage.candidatesTokenCount ?? 0,
    };
  }
  if (parsed.kind === 'ollama' && parsed.usage) {
    return {
      inputTokens: parsed.usage.prompt_eval_count ?? 0,
      outputTokens: parsed.usage.eval_count ?? 0,
    };
  }
  return null;
}

// ---------------------------------------------------------------------------
// Map parsed kind to provider string for pricing
// ---------------------------------------------------------------------------

function kindToProvider(kind: string): string {
  switch (kind) {
    case 'anthropic': return 'anthropic';
    case 'openai': return 'openai';
    case 'openai_responses': return 'openai_responses';
    case 'gemini': return 'gemini';
    case 'ollama': return 'ollama';
    default: return kind;
  }
}

function kindLabel(kind: string): string {
  switch (kind) {
    case 'anthropic': return 'Anthropic';
    case 'openai': return 'OpenAI';
    case 'openai_responses': return 'OpenAI Responses';
    case 'gemini': return 'Gemini';
    case 'ollama': return 'Ollama';
    default: return kind;
  }
}

// ---------------------------------------------------------------------------
// Rollup row
// ---------------------------------------------------------------------------

interface RollupRow {
  provider: string;
  providerLabel: string;
  model: string;
  requestCount: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  estimatedCost: number | null;
}

// ---------------------------------------------------------------------------
// Build rollup from proxied requests
// ---------------------------------------------------------------------------

function buildRollup(proxiedRequests: Array<{ value: Record<string, unknown> }>): RollupRow[] {
  const map = new Map<string, RollupRow>();

  for (const item of proxiedRequests) {
    const parsed = parseTraffic(item.value);
    const tokens = extractTokens(parsed);
    if (tokens === null) continue;

    const provider = kindToProvider(parsed.kind);
    const model = ('model' in parsed && typeof parsed.model === 'string') ? parsed.model : 'unknown';
    const key = `${provider}::${model}`;

    let row = map.get(key);
    if (!row) {
      row = {
        provider,
        providerLabel: kindLabel(parsed.kind),
        model,
        requestCount: 0,
        totalInputTokens: 0,
        totalOutputTokens: 0,
        estimatedCost: null,
      };
      map.set(key, row);
    }

    row.requestCount += 1;
    row.totalInputTokens += tokens.inputTokens;
    row.totalOutputTokens += tokens.outputTokens;
  }

  // Compute costs
  for (const row of map.values()) {
    row.estimatedCost = estimateCostUsd(
      row.provider,
      row.model,
      row.totalInputTokens,
      row.totalOutputTokens,
    );
  }

  // Sort by cost descending (null costs sort last)
  return Array.from(map.values()).sort((a, b) => {
    const aCost = a.estimatedCost ?? -1;
    const bCost = b.estimatedCost ?? -1;
    return bCost - aCost;
  });
}

function formatCost(cost: number | null): string {
  if (cost === null) return '—'; // em-dash
  if (cost === 0) return '$0.00';
  if (cost < 0.01) return `$${cost.toFixed(4)}`;
  return `$${cost.toFixed(2)}`;
}

function formatTokenCount(count: number): string {
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(1)}M`;
  if (count >= 1_000) return `${(count / 1_000).toFixed(1)}K`;
  return count.toString();
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function LlmUsageTile() {
  const proxiedRequests = useDashboardStore((s) => s.proxiedRequests);

  const rollup = useMemo(() => buildRollup(proxiedRequests), [proxiedRequests]);

  const totalCost = useMemo(() => {
    let sum = 0;
    let hasKnown = false;
    for (const row of rollup) {
      if (row.estimatedCost !== null) {
        sum += row.estimatedCost;
        hasKnown = true;
      }
    }
    return hasKnown ? sum : null;
  }, [rollup]);

  // Provider shares for header chips
  const providerShares = useMemo(() => {
    if (totalCost === null || totalCost === 0) return [];
    const shares = new Map<string, number>();
    for (const row of rollup) {
      if (row.estimatedCost !== null && row.estimatedCost > 0) {
        const existing = shares.get(row.providerLabel) ?? 0;
        shares.set(row.providerLabel, existing + row.estimatedCost);
      }
    }
    return Array.from(shares.entries())
      .map(([label, cost]) => ({
        label,
        percentage: Math.round((cost / totalCost) * 100),
      }))
      .sort((a, b) => b.percentage - a.percentage);
  }, [rollup, totalCost]);

  if (rollup.length === 0) {
    return (
      <Box sx={{ p: 2, textAlign: 'center' }}>
        <Typography variant="body2" color="text.secondary">
          No LLM traffic captured yet
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
          Configure your application to proxy through MockServer to see usage analytics.
        </Typography>
      </Box>
    );
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
      {/* Header */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 1,
          px: 1,
          py: 0.5,
          borderBottom: 1,
          borderColor: 'divider',
          flexShrink: 0,
          flexWrap: 'wrap',
        }}
      >
        <Typography variant="subtitle2" sx={{ fontWeight: 700, fontSize: '0.8rem' }}>
          LLM Usage
        </Typography>
        {totalCost !== null && (
          <Typography variant="subtitle2" sx={{ fontWeight: 700, fontSize: '0.8rem', color: 'primary.main' }}>
            {formatCost(totalCost)}
          </Typography>
        )}
        {providerShares.map((share) => (
          <Chip
            key={share.label}
            label={`${share.label} ${share.percentage}%`}
            size="small"
            variant="outlined"
            sx={{ height: 18, fontSize: '0.6rem', '& .MuiChip-label': { px: 0.5 } }}
          />
        ))}
      </Box>

      {/* Table */}
      <TableContainer sx={{ flex: 1, overflowY: 'auto' }}>
        <Table size="small" stickyHeader>
          <TableHead>
            <TableRow>
              <TableCell sx={{ fontSize: '0.7rem', fontWeight: 600, py: 0.5 }}>Provider</TableCell>
              <TableCell sx={{ fontSize: '0.7rem', fontWeight: 600, py: 0.5 }}>Model</TableCell>
              <TableCell sx={{ fontSize: '0.7rem', fontWeight: 600, py: 0.5 }} align="right">Requests</TableCell>
              <TableCell sx={{ fontSize: '0.7rem', fontWeight: 600, py: 0.5 }} align="right">Input Tokens</TableCell>
              <TableCell sx={{ fontSize: '0.7rem', fontWeight: 600, py: 0.5 }} align="right">Output Tokens</TableCell>
              <TableCell sx={{ fontSize: '0.7rem', fontWeight: 600, py: 0.5 }} align="right">Est. Cost</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rollup.map((row) => (
              <TableRow key={`${row.provider}::${row.model}`} hover>
                <TableCell sx={{ fontSize: '0.7rem', py: 0.25 }}>{row.providerLabel}</TableCell>
                <TableCell sx={{ fontSize: '0.7rem', py: 0.25, fontFamily: 'monospace' }}>{row.model}</TableCell>
                <TableCell sx={{ fontSize: '0.7rem', py: 0.25 }} align="right">{row.requestCount}</TableCell>
                <TableCell sx={{ fontSize: '0.7rem', py: 0.25 }} align="right">{formatTokenCount(row.totalInputTokens)}</TableCell>
                <TableCell sx={{ fontSize: '0.7rem', py: 0.25 }} align="right">{formatTokenCount(row.totalOutputTokens)}</TableCell>
                <TableCell sx={{ fontSize: '0.7rem', py: 0.25, fontWeight: 600 }} align="right">{formatCost(row.estimatedCost)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Box>
  );
}
