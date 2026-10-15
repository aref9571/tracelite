import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, createApiClient, toIssueListParameters } from './client.ts';
import type { Issue, Page } from './types.ts';

const API_KEY = 'a'.repeat(64);

const issue: Issue = {
  id: '5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b',
  application: 'checkout-service',
  environment: 'production',
  fingerprint: 'f'.repeat(64),
  exceptionType: 'java.lang.IllegalStateException',
  message: 'Cart is empty',
  status: 'UNRESOLVED',
  occurrenceCount: 42,
  firstSeenAt: '2026-10-12T09:00:00.123456Z',
  lastSeenAt: '2026-10-13T07:45:10.654321Z',
  resolvedAt: null,
};

const issuePage: Page<Issue> = { items: [issue], page: 0, size: 20, totalItems: 1, totalPages: 1 };

function jsonResponse(status: number, body: unknown, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

function stubFetch(implementation: (input: string, init?: RequestInit) => Promise<Response>) {
  const fetchMock = vi.fn(implementation);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

async function captureError(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('Expected the request to fail.');
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('toIssueListParameters', () => {
  it('always sends page and size', () => {
    expect(toIssueListParameters({ page: 0, size: 20 }).toString()).toBe('page=0&size=20');
  });

  it('adds status and environment when they are set', () => {
    const parameters = toIssueListParameters({ status: 'RESOLVED', environment: 'eu-west:1', page: 2, size: 20 });

    expect(parameters.toString()).toBe('status=RESOLVED&environment=eu-west%3A1&page=2&size=20');
  });
});

describe('createApiClient', () => {
  it('lists issues with the key header and the query parameters', async () => {
    const fetchMock = stubFetch(async () => jsonResponse(200, issuePage));
    const client = createApiClient('http://localhost:8080/', API_KEY);

    const result = await client.listIssues({ status: 'UNRESOLVED', page: 1, size: 20 });

    expect(result).toEqual(issuePage);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://localhost:8080/api/v1/issues?status=UNRESOLVED&page=1&size=20');
    expect(init?.method).toBe('GET');
    expect(init?.headers).toEqual({ Accept: 'application/json', 'X-API-Key': API_KEY });
  });

  it('reads one issue and encodes the ID in the path', async () => {
    const fetchMock = stubFetch(async () => jsonResponse(200, issue));
    const client = createApiClient('http://localhost:8080', API_KEY);

    await expect(client.getIssue(issue.id)).resolves.toEqual(issue);
    await client.getIssue('a/b?c').catch(() => undefined);

    expect(fetchMock.mock.calls[0]![0]).toBe(`http://localhost:8080/api/v1/issues/${issue.id}`);
    expect(fetchMock.mock.calls[1]![0]).toBe('http://localhost:8080/api/v1/issues/a%2Fb%3Fc');
  });

  it('turns a problem detail into an HTTP error with its field errors', async () => {
    const problem = {
      title: 'Invalid request',
      status: 400,
      detail: 'The request contains invalid fields.',
      errors: [{ field: 'environment', message: 'must match the allowed pattern' }],
    };
    stubFetch(async () => jsonResponse(400, problem, 'application/problem+json'));
    const client = createApiClient('http://localhost:8080', API_KEY);

    const error = await captureError(client.listIssues({ environment: 'bad value', page: 0, size: 20 }));

    expect(error.kind).toBe('http');
    expect(error.status).toBe(400);
    expect(error.message).toBe('The request contains invalid fields.');
    expect(error.problem?.errors).toEqual(problem.errors);
  });

  it('reports a rejected key to the unauthorized callback', async () => {
    stubFetch(async () =>
      jsonResponse(401, { title: 'Unauthorized', status: 401, detail: 'A valid API key is required in the X-API-Key header.' }),
    );
    const onUnauthorized = vi.fn();
    const client = createApiClient('http://localhost:8080', API_KEY, onUnauthorized);

    const error = await captureError(client.listIssues({ page: 0, size: 20 }));

    expect(error.status).toBe(401);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('does not call the unauthorized callback for a forbidden key', async () => {
    stubFetch(async () => jsonResponse(403, { title: 'Forbidden', status: 403 }));
    const onUnauthorized = vi.fn();
    const client = createApiClient('http://localhost:8080', API_KEY, onUnauthorized);

    const error = await captureError(client.listIssues({ page: 0, size: 20 }));

    expect(error.status).toBe(403);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('uses a generic message when an error response has no problem detail', async () => {
    stubFetch(async () => new Response('<html>Bad gateway</html>', { status: 502 }));
    const client = createApiClient('http://localhost:8080', API_KEY);

    const error = await captureError(client.listIssues({ page: 0, size: 20 }));

    expect(error.kind).toBe('http');
    expect(error.problem).toBeNull();
    expect(error.message).toBe('The request failed with status 502.');
  });

  it('reports a network failure without a status', async () => {
    stubFetch(async () => {
      throw new TypeError('Failed to fetch');
    });
    const client = createApiClient('http://localhost:8080', API_KEY);

    const error = await captureError(client.listIssues({ page: 0, size: 20 }));

    expect(error.kind).toBe('network');
    expect(error.status).toBeNull();
  });

  it('rejects a successful response that is not JSON', async () => {
    stubFetch(async () => new Response('not json', { status: 200 }));
    const client = createApiClient('http://localhost:8080', API_KEY);

    const error = await captureError(client.getIssue(issue.id));

    expect(error.kind).toBe('invalid-response');
    expect(error.status).toBe(200);
  });

  it('passes an abort through unchanged', async () => {
    const controller = new AbortController();
    stubFetch(async () => {
      controller.abort();
      throw new DOMException('The operation was aborted.', 'AbortError');
    });
    const client = createApiClient('http://localhost:8080', API_KEY);

    await expect(client.listIssues({ page: 0, size: 20 }, controller.signal)).rejects.toMatchObject({
      name: 'AbortError',
    });
  });
});
