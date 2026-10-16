import { useCallback } from 'react';
import { Link } from 'react-router';
import type { ApiClient } from '../api/client.ts';
import type { IssueEvent, Page } from '../api/types.ts';
import { formatCount, formatDateTime } from '../format.ts';
import { useApiRequest } from '../useApiRequest.ts';
import ErrorNotice from './ErrorNotice.tsx';
import Pagination from './Pagination.tsx';
import StackTrace from './StackTrace.tsx';

export const EVENT_PAGE_SIZE = 10;

interface IssueEventHistoryProps {
  client: ApiClient;
  issueId: string;
  pageNumber: number;
  pageHref: (pageNumber: number) => string;
  linkState: unknown;
}

export default function IssueEventHistory({ client, issueId, pageNumber, pageHref, linkState }: IssueEventHistoryProps) {
  const load = useCallback(
    (signal: AbortSignal) => client.listIssueEvents(issueId, { page: pageNumber - 1, size: EVENT_PAGE_SIZE }, signal),
    [client, issueId, pageNumber],
  );
  const [request, retry] = useApiRequest(load);

  return (
    <section className="event-history" aria-labelledby="event-history-title">
      <h2 id="event-history-title">Event history</h2>
      {request.status === 'loading' && (
        <p className="notice" role="status">
          Loading events...
        </p>
      )}
      {request.status === 'error' && <ErrorNotice error={request.error} onRetry={retry} />}
      {request.status === 'success' && (
        <EventResults result={request.data} pageHref={pageHref} linkState={linkState} />
      )}
    </section>
  );
}

interface EventResultsProps {
  result: Page<IssueEvent>;
  pageHref: (pageNumber: number) => string;
  linkState: unknown;
}

function EventResults({ result, pageHref, linkState }: EventResultsProps) {
  const pageNumber = result.page + 1;

  if (result.totalItems === 0) {
    return (
      <div className="notice">
        <p>No events are stored for this issue.</p>
      </div>
    );
  }

  if (result.items.length === 0) {
    return (
      <div className="notice">
        <p>
          Page {pageNumber} is past the last page of events.{' '}
          <Link to={pageHref(result.totalPages)} state={linkState}>
            Go to page {result.totalPages}
          </Link>
        </p>
      </div>
    );
  }

  const firstItem = result.page * result.size + 1;
  const lastItem = firstItem + result.items.length - 1;

  return (
    <>
      <p className="result-summary">
        Showing {formatCount(firstItem)} to {formatCount(lastItem)} of {formatCount(result.totalItems)} events, newest
        first
      </p>
      <ol className="event-list">
        {result.items.map((event, index) => (
          <EventItem key={event.id} event={event} expanded={result.page === 0 && index === 0} />
        ))}
      </ol>
      <Pagination
        pageNumber={pageNumber}
        totalPages={result.totalPages}
        pageHref={pageHref}
        label="Event pages"
        linkState={linkState}
      />
    </>
  );
}

function EventItem({ event, expanded }: { event: IssueEvent; expanded: boolean }) {
  return (
    <li className="panel event">
      <dl className="event-facts">
        <dt>Received</dt>
        <dd>
          <time dateTime={event.receivedAt}>{formatDateTime(event.receivedAt)}</time>
        </dd>
        <dt>Occurred</dt>
        <dd>
          <time dateTime={event.occurredAt}>{formatDateTime(event.occurredAt)}</time>
        </dd>
        <dt>Correlation ID</dt>
        <dd>{event.correlationId ? <code>{event.correlationId}</code> : 'None'}</dd>
      </dl>
      {event.message && <p className="event-message">{event.message}</p>}
      {event.stackTrace ? (
        <details className="event-section" open={expanded}>
          <summary>Stack trace</summary>
          <StackTrace stackTrace={event.stackTrace} />
        </details>
      ) : (
        <p className="event-section event-missing">No stack trace was sent with this event.</p>
      )}
      {event.context && Object.keys(event.context).length > 0 && (
        <details className="event-section">
          <summary>Context ({formatCount(Object.keys(event.context).length)})</summary>
          <pre className="event-context">
            <code>{JSON.stringify(event.context, null, 2)}</code>
          </pre>
        </details>
      )}
    </li>
  );
}
