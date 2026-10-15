import type { Issue, IssueListQuery, Page, ProblemDetail } from './types.ts';

export type ApiErrorKind = 'network' | 'http' | 'invalid-response' | 'unexpected';

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number | null;
  readonly problem: ProblemDetail | null;

  constructor(message: string, kind: ApiErrorKind, status: number | null = null, problem: ProblemDetail | null = null) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.problem = problem;
  }
}

export interface ApiClient {
  listIssues(query: IssueListQuery, signal?: AbortSignal): Promise<Page<Issue>>;
  getIssue(id: string, signal?: AbortSignal): Promise<Issue>;
}

export function createApiClient(baseUrl: string, apiKey: string, onUnauthorized?: () => void): ApiClient {
  const normalizedBaseUrl = baseUrl.replace(/\/+$/, '');

  async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
    let response: Response;
    try {
      response = await fetch(normalizedBaseUrl + path, {
        method: 'GET',
        headers: { Accept: 'application/json', 'X-API-Key': apiKey },
        signal,
      });
    } catch (error) {
      if (signal?.aborted) {
        throw error;
      }
      throw new ApiError('The backend could not be reached.', 'network');
    }

    const body = await readJson(response);
    if (!response.ok) {
      if (response.status === 401) {
        onUnauthorized?.();
      }
      const problem = isProblemDetail(body) ? body : null;
      throw new ApiError(
        problem?.detail ?? `The request failed with status ${response.status}.`,
        'http',
        response.status,
        problem,
      );
    }
    if (body === undefined) {
      throw new ApiError('The backend returned a response that is not valid JSON.', 'invalid-response', response.status);
    }
    return body as T;
  }

  return {
    listIssues(query, signal) {
      return getJson<Page<Issue>>(`/api/v1/issues?${toIssueListParameters(query)}`, signal);
    },
    getIssue(id, signal) {
      return getJson<Issue>(`/api/v1/issues/${encodeURIComponent(id)}`, signal);
    },
  };
}

export function toIssueListParameters(query: IssueListQuery): URLSearchParams {
  const parameters = new URLSearchParams();
  if (query.status) {
    parameters.set('status', query.status);
  }
  if (query.environment) {
    parameters.set('environment', query.environment);
  }
  parameters.set('page', String(query.page));
  parameters.set('size', String(query.size));
  return parameters;
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text === '') {
    return undefined;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return undefined;
  }
}

function isProblemDetail(value: unknown): value is ProblemDetail {
  return typeof value === 'object' && value !== null && ('title' in value || 'detail' in value);
}
