import { useCallback, useState } from 'react';
import { Link, useLocation, useParams, useSearchParams } from 'react-router';
import type { ApiClient } from '../api/client.ts';
import type { Issue } from '../api/types.ts';
import ErrorNotice from '../components/ErrorNotice.tsx';
import IssueEventHistory from '../components/IssueEventHistory.tsx';
import IssueStatusActions from '../components/IssueStatusActions.tsx';
import StatusBadge from '../components/StatusBadge.tsx';
import { formatCount, formatDateTime } from '../format.ts';
import { parsePageNumber } from '../issueListSearch.ts';
import { useApiRequest } from '../useApiRequest.ts';

interface IssueDetailPageProps {
  client: ApiClient;
}

export default function IssueDetailPage({ client }: IssueDetailPageProps) {
  const { issueId = '' } = useParams();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const backHref = `/${listSearchFrom(location.state)}`;
  const eventPageNumber = parsePageNumber(searchParams.get('page'));
  const issueHref = `/issues/${encodeURIComponent(issueId)}`;
  const eventPageHref = (pageNumber: number) => (pageNumber > 1 ? `${issueHref}?page=${pageNumber}` : issueHref);

  const load = useCallback((signal: AbortSignal) => client.getIssue(issueId, signal), [client, issueId]);
  const [request, retry] = useApiRequest(load);
  const [statusChange, setStatusChange] = useState<{ loaded: Issue; current: Issue } | null>(null);

  return (
    <section>
      <p>
        <Link to={backHref}>Back to issues</Link>
      </p>
      {request.status === 'loading' && (
        <p className="notice" role="status">
          Loading issue...
        </p>
      )}
      {request.status === 'error' &&
        (request.error.status === 404 || request.error.status === 400 ? (
          <div className="notice">
            <h1>Issue not found</h1>
            <p>No issue exists with this ID. It may have been typed incorrectly.</p>
          </div>
        ) : (
          <ErrorNotice error={request.error} onRetry={retry} />
        ))}
      {request.status === 'success' && (
        <>
          <IssueSummary
            issue={statusChange?.loaded === request.data ? statusChange.current : request.data}
            client={client}
            onIssueChange={(current) => setStatusChange({ loaded: request.data, current })}
          />
          <IssueEventHistory
            client={client}
            issueId={issueId}
            pageNumber={eventPageNumber}
            pageHref={eventPageHref}
            linkState={location.state}
          />
        </>
      )}
    </section>
  );
}

interface IssueSummaryProps {
  issue: Issue;
  client: ApiClient;
  onIssueChange: (issue: Issue) => void;
}

function IssueSummary({ issue, client, onIssueChange }: IssueSummaryProps) {
  return (
    <article className="panel">
      <h1 className="issue-title">{issue.exceptionType}</h1>
      {issue.message && <p className="issue-detail-message">{issue.message}</p>}
      <IssueStatusActions key={issue.id} issue={issue} client={client} onIssueChange={onIssueChange} />
      <dl className="issue-facts">
        <dt>Status</dt>
        <dd>
          <StatusBadge status={issue.status} />
        </dd>
        <dt>Application</dt>
        <dd>{issue.application}</dd>
        <dt>Environment</dt>
        <dd>{issue.environment}</dd>
        <dt>Events</dt>
        <dd>{formatCount(issue.occurrenceCount)}</dd>
        <dt>First seen</dt>
        <dd>
          <time dateTime={issue.firstSeenAt}>{formatDateTime(issue.firstSeenAt)}</time>
        </dd>
        <dt>Last seen</dt>
        <dd>
          <time dateTime={issue.lastSeenAt}>{formatDateTime(issue.lastSeenAt)}</time>
        </dd>
        {issue.resolvedAt && (
          <>
            <dt>Resolved</dt>
            <dd>
              <time dateTime={issue.resolvedAt}>{formatDateTime(issue.resolvedAt)}</time>
            </dd>
          </>
        )}
        <dt>Fingerprint</dt>
        <dd>
          <code className="fingerprint">{issue.fingerprint}</code>
        </dd>
        <dt>Issue ID</dt>
        <dd>
          <code>{issue.id}</code>
        </dd>
      </dl>
    </article>
  );
}

function listSearchFrom(state: unknown): string {
  if (typeof state === 'object' && state !== null && 'listSearch' in state && typeof state.listSearch === 'string') {
    return state.listSearch === '' ? '' : `?${state.listSearch}`;
  }
  return '';
}
