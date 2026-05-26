import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import PredicatePills from '../components/PredicatePills';
import type { ConversationPredicates } from '../lib/llmTraffic';

describe('PredicatePills', () => {
  it('renders turnIndex pill', () => {
    const predicates: ConversationPredicates = { turnIndex: 2 };
    render(<PredicatePills predicates={predicates} />);
    expect(screen.getByText('turn = 2')).toBeInTheDocument();
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

  it('renders all pills when all predicates are set', () => {
    const predicates: ConversationPredicates = {
      turnIndex: 1,
      latestMessageContains: 'hello',
      latestMessageMatches: '\\w+',
      latestMessageRole: 'ASSISTANT',
      containsToolResultFor: 'calculator',
    };
    render(<PredicatePills predicates={predicates} />);
    expect(screen.getByText('turn = 1')).toBeInTheDocument();
    expect(screen.getByText(/latest msg ⊃ "hello"/)).toBeInTheDocument();
    expect(screen.getByText(/latest msg ~ \/\\w\+\//)).toBeInTheDocument();
    expect(screen.getByText('latest role = ASSISTANT')).toBeInTheDocument();
    expect(screen.getByText('has tool_result for calculator')).toBeInTheDocument();
  });

  it('returns null when no predicates are set', () => {
    const predicates: ConversationPredicates = {};
    const { container } = render(<PredicatePills predicates={predicates} />);
    expect(container.innerHTML).toBe('');
  });
});
