import { describe, expect, it } from 'vitest';
import {
  ISSUE_PAGE_SIZE,
  hasActiveFilters,
  isValidEnvironment,
  parseIssueListSearch,
  toIssueListSearch,
} from './issueListSearch.ts';

describe('parseIssueListSearch', () => {
  it('uses the first page and no filters for an empty search', () => {
    expect(parseIssueListSearch(new URLSearchParams())).toEqual({
      status: undefined,
      environment: undefined,
      page: 0,
      size: ISSUE_PAGE_SIZE,
    });
  });

  it('reads status, environment and a one-based page number', () => {
    const query = parseIssueListSearch(new URLSearchParams('status=RESOLVED&environment=%20staging%20&page=3'));

    expect(query).toEqual({ status: 'RESOLVED', environment: 'staging', page: 2, size: ISSUE_PAGE_SIZE });
  });

  it.each(['resolved', 'OPEN', ''])('ignores the unknown status %j', (status) => {
    expect(parseIssueListSearch(new URLSearchParams({ status })).status).toBeUndefined();
  });

  it.each(['0', '-1', '1.5', 'abc', '10002', ''])('falls back to the first page for %j', (page) => {
    expect(parseIssueListSearch(new URLSearchParams({ page })).page).toBe(0);
  });

  it('accepts the highest page the backend allows', () => {
    expect(parseIssueListSearch(new URLSearchParams({ page: '10001' })).page).toBe(10_000);
  });

  it('treats a blank environment as no filter', () => {
    expect(parseIssueListSearch(new URLSearchParams({ environment: '   ' })).environment).toBeUndefined();
  });
});

describe('toIssueListSearch', () => {
  it('leaves out empty filters and the first page', () => {
    expect(toIssueListSearch({}).toString()).toBe('');
    expect(toIssueListSearch({}, 1).toString()).toBe('');
  });

  it('writes filters and later page numbers', () => {
    expect(toIssueListSearch({ status: 'UNRESOLVED', environment: 'production' }, 4).toString()).toBe(
      'status=UNRESOLVED&environment=production&page=4',
    );
  });

  it('round-trips through parseIssueListSearch', () => {
    const search = toIssueListSearch({ status: 'RESOLVED', environment: 'eu-west:1' }, 2);

    expect(parseIssueListSearch(search)).toEqual({
      status: 'RESOLVED',
      environment: 'eu-west:1',
      page: 1,
      size: ISSUE_PAGE_SIZE,
    });
  });
});

describe('hasActiveFilters', () => {
  it('is true only when a status or environment is set', () => {
    expect(hasActiveFilters({})).toBe(false);
    expect(hasActiveFilters({ status: 'RESOLVED' })).toBe(true);
    expect(hasActiveFilters({ environment: 'production' })).toBe(true);
  });
});

describe('isValidEnvironment', () => {
  it.each(['production', 'eu-west:1', 'v1.2_beta', 'a'.repeat(50)])('accepts %j', (value) => {
    expect(isValidEnvironment(value)).toBe(true);
  });

  it.each(['', 'with space', 'a'.repeat(51), 'prod/eu', 'staging%'])('rejects %j', (value) => {
    expect(isValidEnvironment(value)).toBe(false);
  });
});
