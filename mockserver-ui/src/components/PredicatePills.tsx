import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import type { ConversationPredicates } from '../lib/llmTraffic';

interface PredicatePillsProps {
  predicates: ConversationPredicates;
}

interface PillDef {
  key: string;
  label: string;
}

// Cap user-supplied values rendered in pill labels so a long regex source or
// substring (whether accidental or deliberately crafted) cannot break the
// dashboard layout. Chip labels are React nodes (not HTML), so this is a
// length-only concern — not an XSS one.
const MAX_PILL_VALUE_LENGTH = 60;

function truncate(value: string): string {
  if (value.length <= MAX_PILL_VALUE_LENGTH) return value;
  return value.substring(0, MAX_PILL_VALUE_LENGTH) + '…';
}

function buildPills(predicates: ConversationPredicates): PillDef[] {
  const pills: PillDef[] = [];

  if (predicates.turnIndex != null) {
    pills.push({ key: 'turnIndex', label: `turn = ${predicates.turnIndex}` });
  }
  if (predicates.latestMessageContains != null) {
    pills.push({
      key: 'latestMessageContains',
      label: `latest msg ⊃ "${truncate(predicates.latestMessageContains)}"`,
    });
  }
  if (predicates.latestMessageMatches != null) {
    pills.push({
      key: 'latestMessageMatches',
      label: `latest msg ~ /${truncate(predicates.latestMessageMatches)}/`,
    });
  }
  if (predicates.latestMessageRole != null) {
    pills.push({
      key: 'latestMessageRole',
      label: `latest role = ${predicates.latestMessageRole}`,
    });
  }
  if (predicates.containsToolResultFor != null) {
    pills.push({
      key: 'containsToolResultFor',
      label: `has tool_result for ${truncate(predicates.containsToolResultFor)}`,
    });
  }

  return pills;
}

export default function PredicatePills({ predicates }: PredicatePillsProps) {
  const pills = buildPills(predicates);
  if (pills.length === 0) return null;

  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
      {pills.map((pill) => (
        <Chip
          key={pill.key}
          label={pill.label}
          size="small"
          color="info"
          variant="outlined"
          sx={{ height: 22, fontSize: '0.7rem' }}
        />
      ))}
    </Box>
  );
}
