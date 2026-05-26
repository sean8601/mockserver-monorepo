import { Fragment, useState, useMemo } from 'react';
import type React from 'react';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import IconButton from '@mui/material/IconButton';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import SmartToyIcon from '@mui/icons-material/SmartToy';
import BoltIcon from '@mui/icons-material/Bolt';
import type { JsonListItem as JsonListItemType } from '../types';
import type { ConversationPredicates } from '../lib/llmTraffic';
import JsonViewer from './JsonViewer';
import DescriptionDisplay from './DescriptionDisplay';
import PredicatePills from './PredicatePills';

interface JsonListItemProps {
  item: JsonListItemType;
  index: number;
}

const PROVIDER_LABELS: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
  OPENAI_RESPONSES: 'OpenAI Responses',
  GEMINI: 'Gemini',
  BEDROCK: 'Bedrock',
  AZURE_OPENAI: 'Azure OpenAI',
  OLLAMA: 'Ollama',
};

interface LlmBadgeInfo {
  provider: string;
  model: string | null;
  textPreview: string | null;
  streaming: boolean;
  toolCallCount: number;
  isEmbedding: boolean;
  isStateful: boolean;
  turnIndex: number | null;
  isolationLabel: string | null;
  predicates: ConversationPredicates | null;
}

function extractIsolationLabel(scenarioName: string | undefined): string | null {
  if (!scenarioName) return null;
  const isoMatch = /__iso=([^_]+):(.+)$/.exec(scenarioName);
  if (!isoMatch) return null;
  return `${isoMatch[1]}:${isoMatch[2]}`;
}

function extractLlmBadge(value: Record<string, unknown>): LlmBadgeInfo | null {
  const llm = value['httpLlmResponse'] as Record<string, unknown> | undefined;
  if (!llm) return null;

  const providerRaw = llm['provider'] as string | undefined;
  if (!providerRaw) return null;

  const provider = PROVIDER_LABELS[providerRaw] ?? providerRaw;
  const model = (llm['model'] as string | undefined) ?? null;

  let textPreview: string | null = null;
  const completion = llm['completion'] as Record<string, unknown> | undefined;
  const streaming = (llm['streaming'] as boolean | undefined) === true;
  let toolCallCount = 0;
  if (completion) {
    const text = completion['text'] as string | undefined;
    if (text) {
      textPreview = text.length > 80 ? text.substring(0, 80) + '…' : text;
    }
    const toolCalls = completion['toolCalls'] as unknown[] | undefined;
    if (toolCalls) {
      toolCallCount = toolCalls.length;
    }
  }

  const isEmbedding = 'embedding' in llm;

  const predicatesObj = llm['conversationPredicates'] as ConversationPredicates | undefined;
  const isStateful = predicatesObj != null;
  const turnIndex = predicatesObj?.turnIndex ?? null;

  // We are already past the `if (!llm) return null` guard, so the scenario name
  // can only be read from the LLM action itself. The httpResponseObjectCallback
  // path the badge previously consulted was unreachable.
  const scenarioName = llm['scenarioName'] as string | undefined;
  const isolationLabel = extractIsolationLabel(scenarioName);

  return {
    provider,
    model,
    textPreview,
    streaming,
    toolCallCount,
    isEmbedding,
    isStateful,
    turnIndex,
    isolationLabel,
    predicates: predicatesObj ?? null,
  };
}

export default function JsonListItem({ item, index }: JsonListItemProps) {
  const [expanded, setExpanded] = useState(false);
  const llmBadge = useMemo(() => extractLlmBadge(item.value), [item.value]);

  return (
    <Box
      sx={{
        position: 'relative',
        py: 0.5,
        px: 1,
        borderBottom: 1,
        borderColor: 'divider',
        '&:hover .copy-btn': { opacity: 1 },
        '&:last-child': { borderBottom: 0 },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
          cursor: 'pointer',
          userSelect: 'none',
        }}
        onClick={() => setExpanded((prev) => !prev)}
      >
        <IconButton size="small" sx={{ p: 0, '& .MuiSvgIcon-root': { fontSize: '1rem' } }}>
          {expanded ? <ExpandMoreIcon /> : <ChevronRightIcon />}
        </IconButton>
        <Box
          component="span"
          sx={{ fontFamily: 'monospace', fontSize: '0.8em', color: 'text.secondary', minWidth: 24 }}
        >
          {index}
        </Box>
        {item.description && <DescriptionDisplay description={item.description} />}
        {llmBadge && (() => {
          // Build optional signal chips (provider chip is mandatory and renders separately).
          const optionalChips: Array<{ key: string; element: React.ReactElement; tooltipLabel: string }> = [];
          if (llmBadge.streaming) {
            optionalChips.push({
              key: 'stream',
              tooltipLabel: 'streaming',
              element: (
                <Chip
                  icon={<BoltIcon sx={{ fontSize: '0.8rem' }} />}
                  label="stream"
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }
          if (llmBadge.toolCallCount > 0) {
            const lbl = `${llmBadge.toolCallCount} tool${llmBadge.toolCallCount > 1 ? 's' : ''}`;
            optionalChips.push({
              key: 'tools',
              tooltipLabel: lbl,
              element: (
                <Chip
                  label={lbl}
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }
          if (llmBadge.isEmbedding) {
            optionalChips.push({
              key: 'embedding',
              tooltipLabel: 'embedding',
              element: (
                <Chip
                  label="embedding"
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }
          if (llmBadge.isStateful) {
            const lbl = llmBadge.turnIndex != null ? `turn ${llmBadge.turnIndex}` : 'stateful';
            optionalChips.push({
              key: 'stateful',
              tooltipLabel: lbl,
              element: (
                <Chip
                  label={lbl}
                  size="small"
                  color="info"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }
          if (llmBadge.isolationLabel) {
            const lbl = `iso=${llmBadge.isolationLabel}`;
            optionalChips.push({
              key: 'isolation',
              tooltipLabel: lbl,
              element: (
                <Chip
                  label={lbl}
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              ),
            });
          }

          // Cap at 3 visible optional chips (4 total including the mandatory provider chip).
          // Surplus is collapsed into a "+N more" chip with a tooltip listing them.
          const MAX_OPTIONAL = 3;
          const visibleChips = optionalChips.slice(0, MAX_OPTIONAL);
          const hiddenChips = optionalChips.slice(MAX_OPTIONAL);

          return (
          <Box sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, ml: 0.5, flexWrap: 'wrap' }}>
            <Chip
              icon={<SmartToyIcon sx={{ fontSize: '0.85rem' }} />}
              label={`LLM Response – ${llmBadge.provider}${llmBadge.model ? ' / ' + llmBadge.model : ''}`}
              size="small"
              color="secondary"
              variant="outlined"
              sx={{ height: 20, fontSize: '0.65rem' }}
            />
            {visibleChips.map((c) => <Fragment key={c.key}>{c.element}</Fragment>)}
            {hiddenChips.length > 0 && (
              <Tooltip title={hiddenChips.map((c) => c.tooltipLabel).join(', ')}>
                <Chip
                  label={`+${hiddenChips.length} more`}
                  size="small"
                  variant="outlined"
                  sx={{ height: 20, fontSize: '0.65rem' }}
                />
              </Tooltip>
            )}
            {llmBadge.textPreview && (
              <Box
                component="span"
                sx={{
                  fontFamily: 'monospace',
                  fontSize: '0.7em',
                  color: 'text.secondary',
                  maxWidth: 200,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {llmBadge.textPreview}
              </Box>
            )}
          </Box>
          );
        })()}
      </Box>
      {expanded && (
        <Box sx={{ pl: 3.5, pt: 0.5 }}>
          {llmBadge?.predicates && (
            <Box sx={{ mb: 1 }}>
              <PredicatePills predicates={llmBadge.predicates} />
            </Box>
          )}
          <JsonViewer data={item.value} collapsed={1} enableClipboard={true} />
        </Box>
      )}
    </Box>
  );
}
