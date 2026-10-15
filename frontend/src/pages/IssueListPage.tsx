import { useCallback } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { ApiClient } from '../api/client.ts';
import type { Issue, Page } from '../api/types.ts';
import ErrorNotice from '../components/ErrorNotice.tsx';
import IssueFilters from '../components/IssueFilters.tsx';
import Pagination from '../components/Pagination.tsx';
import StatusBadge from '../components/StatusBadge.tsx';
import { formatCount, formatDateTime } from '../format.ts';
import {
  hasActiveFilters,
  parseIssueListSearch,
  toIssueListSearch,
  type IssueListFilters,
} from '../issueListSearch.ts';
import { useApiRequest } from '../useApiRequest.ts';

interface IssueListPageProps {
  client: ApiClient;
}

export default function IssueListPage({ client }: IssueListPageProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  const { status, environment, page, size } = parseIssueListSearch(searchParams);
  const filters: IssueListFilters = { status, environment };
  const listSearch = searchParams.toString();

  const load = useCallback(
    (signal: AbortSignal) => client.listIssues({ status, environment, page, size }, signal),
    [client, status, environment, page, size],
  );
  const [request, retry] = useApiRequest(load);

  const applyFilters = (nextFilters: IssueListFilters) => setSearchParams(toIssueListSearch(nextFilters));
  const pageHref = (pageNumber: number) => `/?${toIssueListSearch(filters, pageNumber)}`;

  return (
    <section>
      <h1>Issues</h1>
      <IssueFilters key={`${status ?? ''}|${environment ?? ''}`} initialFilters={filters} onApply={applyFilters} />
      {request.status === 'loading' && (
        <p className="notice" role="status">
          Loading issues...
        </p>
      )}
      {request.status === 'error' && (
        <ErrorNotice error={request.error} onRetry={retry} />
      )}
      {request.status === 'success' && (
        <IssueResults
          result={request.data}
          filtered={hasActiveFilters(filters)}
          listSearch={listSearch}
          pageHref={pageHref}
          onClearFilters={() => applyFilters({})}
        />
      )}
    </section>
  );
}

interface IssueResultsProps {
  result: Page<Issue>;
  filtered: boolean;
  listSearch: string;
  pageHref: (pageNumber: number) => string;
  onClearFilters: () => void;
}

function IssueResults({ result, filtered, listSearch, pageHref, onClearFilters }: IssueResultsProps) {
  const pageNumber = result.page + 1;

  if (result.totalItems === 0) {
    return filtered ? (
      <div className="notice">
        <p>No issues match these filters.</p>
        <button type="button" className="button-secondary" onClick={onClearFilters}>
          Clear filters
        </button>
      </div>
    ) : (
      <div className="notice">
        <p>No issues have been recorded yet. Issues appear here after an application sends its first error event.</p>
      </div>
    );
  }

  if (result.items.length === 0) {
    return (
      <div className="notice">
        <p>
          Page {pageNumber} is past the last page of results.{' '}
          <Link to={pageHref(result.totalPages)}>Go to page {result.totalPages}</Link>
        </p>
      </div>
    );
  }

  const firstItem = result.page * result.size + 1;
  const lastItem = firstItem + result.items.length - 1;

  return (
    <>
      <p className="result-summary">
        Showing {formatCount(firstItem)} to {formatCount(lastItem)} of {formatCount(result.totalItems)} issues
      </p>
      <div className="table-wrapper">
        <table className="issue-table">
          <thead>
            <tr>
              <th scope="col">Issue</th>
              <th scope="col">Status</th>
              <th scope="col">Environment</th>
              <th scope="col">Application</th>
              <th scope="col" className="numeric">
                Events
              </th>
              <th scope="col">Last seen</th>
            </tr>
          </thead>
          <tbody>
            {result.items.map((issue) => (
              <tr key={issue.id}>
                <td>
                  <Link to={`/issues/${issue.id}`} state={{ listSearch }} className="issue-link">
                    {issue.exceptionType}
                  </Link>
                  {issue.message && <span className="issue-message">{issue.message}</span>}
                </td>
                <td>
                  <StatusBadge status={issue.status} />
                </td>
                <td>{issue.environment}</td>
                <td>{issue.application}</td>
                <td className="numeric">{formatCount(issue.occurrenceCount)}</td>
                <td>
                  <time dateTime={issue.lastSeenAt}>{formatDateTime(issue.lastSeenAt)}</time>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Pagination pageNumber={pageNumber} totalPages={result.totalPages} pageHref={pageHref} />
    </>
  );
}
