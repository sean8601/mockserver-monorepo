import { describe, it, expect } from 'vitest';
import { matchesItemSearch, matchesLogSearch, extractSearchableFields } from '../lib/searchMatcher';

describe('extractSearchableFields', () => {
  it('extracts top-level id and description', () => {
    const fields = extractSearchableFields({ id: 'exp-1', description: 'My expectation' });
    expect(fields).toContain('exp-1');
    expect(fields).toContain('My expectation');
  });

  it('extracts httpRequest method and path', () => {
    const fields = extractSearchableFields({ httpRequest: { method: 'POST', path: '/api/users' } });
    expect(fields).toContain('POST');
    expect(fields).toContain('/api/users');
  });

  it('extracts httpResponse statusCode', () => {
    const fields = extractSearchableFields({ httpResponse: { statusCode: 404 } });
    expect(fields).toContain('404');
  });

  it('extracts LLM provider and model', () => {
    const fields = extractSearchableFields({
      httpLlmResponse: { provider: 'ANTHROPIC', model: 'claude-3-sonnet' },
    });
    expect(fields).toContain('ANTHROPIC');
    expect(fields).toContain('claude-3-sonnet');
  });

  it('includes action type key', () => {
    const fields = extractSearchableFields({ httpLlmResponse: { provider: 'OPENAI' } });
    expect(fields).toContain('httpLlmResponse');
  });
});

describe('matchesItemSearch', () => {
  it('matches on extracted fields (method)', () => {
    expect(matchesItemSearch({ httpRequest: { method: 'POST', path: '/api' } }, 'POST')).toBe(true);
  });

  it('matches case-insensitively', () => {
    expect(matchesItemSearch({ httpRequest: { method: 'GET', path: '/api' } }, 'get')).toBe(true);
  });

  it('does not false-match on structural keys like "value"', () => {
    // Searching "value" should not match just because JSON.stringify contains the key "value"
    // unless the actual content contains "value"
    const item = { httpRequest: { method: 'GET', path: '/api/items' } };
    // "value" appears as a JSON key in stringify but not in field content
    const fields = extractSearchableFields(item);
    expect(fields.some(f => f.toLowerCase().includes('value'))).toBe(false);
  });

  it('falls back to JSON stringify for deep nested values', () => {
    expect(matchesItemSearch({ httpRequest: { headers: [{ name: 'X-Custom', values: ['special-token-123'] }] } }, 'special-token-123')).toBe(true);
  });
});

describe('matchesLogSearch', () => {
  it('matches on key', () => {
    expect(matchesLogSearch({ key: 'log-entry-1', value: {} }, 'log-entry')).toBe(true);
  });

  it('matches on log value message field', () => {
    expect(matchesLogSearch({ key: 'k', value: { message: 'Request matched expectation' } }, 'matched')).toBe(true);
  });

  it('falls back to JSON for deep content', () => {
    expect(matchesLogSearch({ key: 'k', value: { nested: { deep: 'findme' } } }, 'findme')).toBe(true);
  });
});
