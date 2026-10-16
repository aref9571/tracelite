import { ISSUE_STATUSES, type IssueListQuery, type IssueStatus } from './api/types.ts';

export const ISSUE_PAGE_SIZE = 20;
export const MAX_PAGE_NUMBER = 10_001;
const ENVIRONMENT_PATTERN = /^[A-Za-z0-9._:-]{1,50}$/;

export interface IssueListFilters {
  status?: IssueStatus;
  environment?: string;
}

export function isValidEnvironment(value: string): boolean {
  return ENVIRONMENT_PATTERN.test(value);
}

export function parseIssueListSearch(search: URLSearchParams): IssueListQuery {
  const status = search.get('status');
  const environment = search.get('environment')?.trim();
  return {
    status: isIssueStatus(status) ? status : undefined,
    environment: environment ? environment : undefined,
    page: parsePageNumber(search.get('page')) - 1,
    size: ISSUE_PAGE_SIZE,
  };
}

export function toIssueListSearch(filters: IssueListFilters, pageNumber = 1): URLSearchParams {
  const search = new URLSearchParams();
  if (filters.status) {
    search.set('status', filters.status);
  }
  if (filters.environment) {
    search.set('environment', filters.environment);
  }
  if (pageNumber > 1) {
    search.set('page', String(pageNumber));
  }
  return search;
}

export function hasActiveFilters(filters: IssueListFilters): boolean {
  return filters.status !== undefined || filters.environment !== undefined;
}

function isIssueStatus(value: string | null): value is IssueStatus {
  return ISSUE_STATUSES.some((status) => status === value);
}

export function parsePageNumber(value: string | null): number {
  if (value === null || !/^\d+$/.test(value)) {
    return 1;
  }
  const pageNumber = Number(value);
  return pageNumber >= 1 && pageNumber <= MAX_PAGE_NUMBER ? pageNumber : 1;
}
