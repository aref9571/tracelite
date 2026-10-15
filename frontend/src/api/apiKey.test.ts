import { describe, expect, it } from 'vitest';
import { isWellFormedApiKey } from './apiKey.ts';

describe('isWellFormedApiKey', () => {
  it.each(['a'.repeat(32), 'A1_-'.repeat(32), '0123456789abcdef'.repeat(4)])('accepts %j', (value) => {
    expect(isWellFormedApiKey(value)).toBe(true);
  });

  it.each(['', 'a'.repeat(31), 'a'.repeat(129), `${'a'.repeat(32)} `, `${'a'.repeat(31)}!`])('rejects %j', (value) => {
    expect(isWellFormedApiKey(value)).toBe(false);
  });
});
