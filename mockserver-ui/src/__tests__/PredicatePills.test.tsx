import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import PredicatePills from '../components/PredicatePills';
import type { ConversationPredicates } from '../lib/llmTraffic';

describe('PredicatePills', () => {
  it('does not render a turnIndex pill — the parent already shows turn N of M', () => {
    const predicates: ConversationPredicates = { turnIndex: 2 };
    const { container } = render(<PredicatePills predicates={predicates} />);
    // Pills container is hidden entirely when no other predicates are set.
    expect(container.innerHTML).toBe('');
    expect(screen.queryByText(/turn = 2/)).not.toBeInTheDocument();
  });

  it('renders latestMessageContains pill', () => {
    const predicates: ConversationPredicates = { latestMessageContains: 'weather' };
    render(<PredicatePills predicates={predicates} />);
    expect(screen.getByText(/latest msg ⊃ "weather"/)).toBeInTheDocument();
  });

  it('renders latestMessageMatches pill', () => {
    const predicates: ConversationPredicates = { latestMessageMatches: '\\d+C' };
    render(<PredicatePills predicates={predicates} />);
    expect(screen.getByText(/latest msg ~ \/\\d\+C\//)).toBeInTheDocument();
  });

  it('renders latestMessageRole pill', () => {
    const predicates: ConversationPredicates = { latestMessageRole: 'USER' };
    render(<PredicatePills predicates={predicates} />);
    expect(screen.getByText('latest role = USER')).toBeInTheDocument();
  });

  it('renders containsToolResultFor pill', () => {
    const predicates: ConversationPredicates = { containsToolResultFor: 'search' };
    render(<PredicatePills predicates={predicates} />);
    expect(screen.getByText('has tool_result for search')).toBeInTheDocument();
  });

  it('renders semanticMatchAgainst pill flagged exploratory', () => {
    const predicates: ConversationPredicates = { semanticMatchAgainst: 'asks about the weather' };
    render(<PredicatePills predicates={predicates} />);
    expect(screen.getByText(/semantic ≈ "asks about the weather" \(exploratory\)/)).toBeInTheDocument();
  });

  it('renders all non-turnIndex pills when those predicates are set', () => {
    const predicates: ConversationPredicates = {
      turnIndex: 1, // intentionally suppressed — see the dedicated test above
      latestMessageContains: 'hello',
      latestMessageMatches: '\\w+',
      latestMessageRole: 'ASSISTANT',
      containsToolResultFor: 'calculator',
      semanticMatchAgainst: 'wants the total',
    };
    render(<PredicatePills predicates={predicates} />);
    expect(screen.queryByText(/turn = 1/)).not.toBeInTheDocument();
    expect(screen.getByText(/latest msg ⊃ "hello"/)).toBeInTheDocument();
    expect(screen.getByText(/latest msg ~ \/\\w\+\//)).toBeInTheDocument();
    expect(screen.getByText('latest role = ASSISTANT')).toBeInTheDocument();
    expect(screen.getByText('has tool_result for calculator')).toBeInTheDocument();
    expect(screen.getByText(/semantic ≈ "wants the total" \(exploratory\)/)).toBeInTheDocument();
  });

  it('returns null when no predicates are set', () => {
    const predicates: ConversationPredicates = {};
    const { container } = render(<PredicatePills predicates={predicates} />);
    expect(container.innerHTML).toBe('');
  });
});
