import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ApiError, type ApiClient } from '../api/client.ts';
import type { Issue, IssueEvent, Page } from '../api/types.ts';
import IssueDetailPage from './IssueDetailPage.tsx';

const ISSUE_ID = '5b1f7c9e-3f4a-4d2b-9a61-0c8e2f3d4a5b';

const unresolvedIssue: Issue = {
  id: ISSUE_ID,
  application: 'checkout-service',
  environment: 'production',
  fingerprint: 'f'.repeat(64),
  exceptionType: 'java.lang.IllegalStateException',
  message: 'Cart is empty',
  status: 'UNRESOLVED',
  occurrenceCount: 12,
  firstSeenAt: '2026-10-12T09:00:00.123456Z',
  lastSeenAt: '2026-10-13T07:45:10.654321Z',
  resolvedAt: null,
};

const resolvedIssue: Issue = { ...unresolvedIssue, status: 'RESOLVED', resolvedAt: '2026-10-13T08:00:00Z' };

function event(number: number, overrides: Partial<IssueEvent> = {}): IssueEvent {
  return {
    id: `00000000-0000-0000-0000-${String(number).padStart(12, '0')}`,
    exceptionType: unresolvedIssue.exceptionType,
    message: `Cart is empty ${number}`,
    stackTrace: `java.lang.IllegalStateException: Cart is empty ${number}\n\tat com.example.cart.CartService.checkout(CartService.java:42)\nCaused by: java.io.IOException: Broken pipe\n\t... 3 more`,
    occurredAt: '2026-10-13T07:45:09Z',
    receivedAt: '2026-10-13T07:45:10.654321Z',
    correlationId: `req-${number}`,
    context: { orderId: `o-${number}` },
    ...overrides,
  };
}

function eventPage(items: IssueEvent[], page = 0, totalItems = items.length): Page<IssueEvent> {
  return { items, page, size: 10, totalItems, totalPages: Math.ceil(totalItems / 10) };
}

function fakeClient(overrides: Partial<ApiClient> = {}) {
  return {
    listIssues: vi.fn<ApiClient['listIssues']>(),
    getIssue: vi.fn<ApiClient['getIssue']>(async () => unresolvedIssue),
    listIssueEvents: vi.fn<ApiClient['listIssueEvents']>(async () => eventPage([event(2), event(1)])),
    resolveIssue: vi.fn<ApiClient['resolveIssue']>(async () => resolvedIssue),
    reopenIssue: vi.fn<ApiClient['reopenIssue']>(async () => unresolvedIssue),
    ...overrides,
  };
}

function renderPage(client: ApiClient, search = '') {
  const entry = { pathname: `/issues/${ISSUE_ID}`, search, state: { listSearch: 'status=UNRESOLVED' } };
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/issues/:issueId" element={<IssueDetailPage client={client} />} />
      </Routes>
    </MemoryRouter>,
  );
}

function statusBadge(): HTMLElement {
  return within(screen.getByRole('article')).getByText(/^(Resolved|Unresolved)$/, { selector: '.status-badge' });
}

function actionMessage(): string {
  return within(screen.getByRole('article')).getByRole('status').textContent ?? '';
}

describe('IssueDetailPage', () => {
  it('shows the issue summary and its newest events with readable stack traces', async () => {
    const client = fakeClient();
    renderPage(client);

    expect(await screen.findByRole('heading', { level: 1, name: unresolvedIssue.exceptionType })).toBeDefined();
    expect(screen.getByText('Cart is empty')).toBeDefined();
    expect(screen.getByText('12')).toBeDefined();
    expect(statusBadge().textContent).toBe('Unresolved');
    expect(screen.getByRole('link', { name: 'Back to issues' }).getAttribute('href')).toBe('/?status=UNRESOLVED');

    expect(await screen.findByText('Showing 1 to 2 of 2 events, newest first')).toBeDefined();
    const events = screen.getAllByRole('listitem');
    expect(events).toHaveLength(2);
    expect(within(events[0]!).getByText('Cart is empty 2')).toBeDefined();
    expect(within(events[0]!).getByText('req-2')).toBeDefined();
    expect(events[0]!.querySelector('details')?.open).toBe(true);
    expect(events[1]!.querySelector('details')?.open).toBe(false);

    const traceLines = events[0]!.querySelectorAll('.stack-line');
    expect(Array.from(traceLines, (line) => line.className)).toEqual([
      'stack-line stack-summary',
      'stack-line stack-frame',
      'stack-line stack-cause',
      'stack-line stack-omitted',
    ]);
    expect(events[0]!.querySelector('.event-context')?.textContent).toBe('{\n  "orderId": "o-2"\n}');
    expect(client.listIssueEvents).toHaveBeenCalledWith(ISSUE_ID, { page: 0, size: 10 }, expect.any(AbortSignal));
  });

  it('says when an event has no stack trace and hides an empty context', async () => {
    const client = fakeClient({
      listIssueEvents: vi.fn(async () =>
        eventPage([event(1, { stackTrace: null, message: null, correlationId: null, context: {} })]),
      ),
    });
    renderPage(client);

    expect(await screen.findByText('No stack trace was sent with this event.')).toBeDefined();
    expect(screen.getByText('None')).toBeDefined();
    expect(screen.queryByText(/^Context/)).toBeNull();
  });

  it('loads the event page named in the URL and links to the neighbouring pages', async () => {
    const client = fakeClient({ listIssueEvents: vi.fn(async () => eventPage([event(11)], 1, 21)) });
    renderPage(client, '?page=2');

    expect(await screen.findByText('Showing 11 to 11 of 21 events, newest first')).toBeDefined();
    expect(client.listIssueEvents).toHaveBeenCalledWith(ISSUE_ID, { page: 1, size: 10 }, expect.any(AbortSignal));
    const pages = screen.getByRole('navigation', { name: 'Event pages' });
    expect(within(pages).getByRole('link', { name: 'Previous' }).getAttribute('href')).toBe(`/issues/${ISSUE_ID}`);
    expect(within(pages).getByRole('link', { name: 'Next' }).getAttribute('href')).toBe(`/issues/${ISSUE_ID}?page=3`);
    expect(screen.getAllByRole('listitem')[0]!.querySelector('details')?.open).toBe(false);
  });

  it('offers the last page when the event page is past the end', async () => {
    const client = fakeClient({ listIssueEvents: vi.fn(async () => eventPage([], 4, 21)) });
    renderPage(client, '?page=5');

    const link = await screen.findByRole('link', { name: 'Go to page 3' });
    expect(link.getAttribute('href')).toBe(`/issues/${ISSUE_ID}?page=3`);
  });

  it('says when no events are stored', async () => {
    const client = fakeClient({ listIssueEvents: vi.fn(async () => eventPage([])) });
    renderPage(client);

    expect(await screen.findByText('No events are stored for this issue.')).toBeDefined();
  });

  it('resolves the issue and shows the status returned by the backend', async () => {
    const client = fakeClient();
    renderPage(client);

    fireEvent.click(await screen.findByRole('button', { name: 'Resolve issue' }));

    expect(await screen.findByRole('button', { name: 'Reopen issue' })).toBeDefined();
    expect(client.resolveIssue).toHaveBeenCalledWith(ISSUE_ID);
    expect(statusBadge().textContent).toBe('Resolved');
    expect(screen.getByText('Resolved', { selector: 'dt' })).toBeDefined();
    expect(actionMessage()).toBe('The issue is resolved.');
  });

  it('reopens a resolved issue', async () => {
    const client = fakeClient({ getIssue: vi.fn(async () => resolvedIssue) });
    renderPage(client);

    fireEvent.click(await screen.findByRole('button', { name: 'Reopen issue' }));

    expect(await screen.findByRole('button', { name: 'Resolve issue' })).toBeDefined();
    expect(client.reopenIssue).toHaveBeenCalledWith(ISSUE_ID);
    expect(statusBadge().textContent).toBe('Unresolved');
    expect(screen.queryByText('Resolved', { selector: 'dt' })).toBeNull();
    expect(actionMessage()).toBe('The issue is reopened.');
  });

  it('disables the button while the request is in progress', async () => {
    let finish: (issue: Issue) => void = () => undefined;
    const client = fakeClient({ resolveIssue: vi.fn(() => new Promise<Issue>((resolve) => (finish = resolve))) });
    renderPage(client);

    fireEvent.click(await screen.findByRole('button', { name: 'Resolve issue' }));

    const pendingButton = screen.getByRole('button', { name: 'Resolving...' }) as HTMLButtonElement;
    expect(pendingButton.disabled).toBe(true);
    fireEvent.click(pendingButton);
    expect(client.resolveIssue).toHaveBeenCalledTimes(1);

    finish(resolvedIssue);
    expect(await screen.findByRole('button', { name: 'Reopen issue' })).toBeDefined();
  });

  it('reloads the issue after a conflict and shows its current status', async () => {
    const conflict = new ApiError('The issue status cannot change from RESOLVED to RESOLVED.', 'http', 409);
    const getIssue = vi.fn<ApiClient['getIssue']>().mockResolvedValueOnce(unresolvedIssue).mockResolvedValue(resolvedIssue);
    const client = fakeClient({ getIssue, resolveIssue: vi.fn(async () => Promise.reject(conflict)) });
    renderPage(client);

    fireEvent.click(await screen.findByRole('button', { name: 'Resolve issue' }));

    expect(await screen.findByRole('button', { name: 'Reopen issue' })).toBeDefined();
    expect(getIssue).toHaveBeenCalledTimes(2);
    expect(statusBadge().textContent).toBe('Resolved');
    expect(actionMessage()).toBe('The issue was changed by another request or a new event. It is now resolved.');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows an error when the reload after a conflict fails', async () => {
    const getIssue = vi
      .fn<ApiClient['getIssue']>()
      .mockResolvedValueOnce(unresolvedIssue)
      .mockRejectedValue(new ApiError('The backend could not be reached.', 'network'));
    const client = fakeClient({
      getIssue,
      resolveIssue: vi.fn(async () => Promise.reject(new ApiError('Conflict', 'http', 409))),
    });
    renderPage(client);

    fireEvent.click(await screen.findByRole('button', { name: 'Resolve issue' }));

    expect((await screen.findByRole('alert')).textContent).toContain('could not be reached');
    expect(statusBadge().textContent).toBe('Unresolved');
  });

  it('keeps the status and explains a forbidden key', async () => {
    const client = fakeClient({
      resolveIssue: vi.fn(async () => Promise.reject(new ApiError('Forbidden', 'http', 403))),
    });
    renderPage(client);

    fireEvent.click(await screen.findByRole('button', { name: 'Resolve issue' }));

    expect((await screen.findByRole('alert')).textContent).toContain('Use the management key, not an ingestion key.');
    expect(statusBadge().textContent).toBe('Unresolved');
    expect((screen.getByRole('button', { name: 'Resolve issue' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('explains a server error on a status change', async () => {
    const client = fakeClient({
      resolveIssue: vi.fn(async () => Promise.reject(new ApiError('Internal error', 'http', 500))),
    });
    renderPage(client);

    fireEvent.click(await screen.findByRole('button', { name: 'Resolve issue' }));

    expect((await screen.findByRole('alert')).textContent).toBe(
      'The backend could not complete the request (status 500).',
    );
    expect(statusBadge().textContent).toBe('Unresolved');
  });

  it('shows an error with a retry when the events cannot be loaded', async () => {
    const listIssueEvents = vi
      .fn<ApiClient['listIssueEvents']>()
      .mockRejectedValueOnce(new ApiError('The backend could not be reached.', 'network'))
      .mockResolvedValue(eventPage([event(1)]));
    renderPage(fakeClient({ listIssueEvents }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('could not be reached');
    expect(screen.getByRole('heading', { level: 1, name: unresolvedIssue.exceptionType })).toBeDefined();

    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Showing 1 to 1 of 1 events, newest first')).toBeDefined();
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });

  it('shows not found and loads no events for an unknown issue', async () => {
    const client = fakeClient({ getIssue: vi.fn(async () => Promise.reject(new ApiError('Not found', 'http', 404))) });
    renderPage(client);

    expect(await screen.findByRole('heading', { name: 'Issue not found' })).toBeDefined();
    expect(client.listIssueEvents).not.toHaveBeenCalled();
  });
});
