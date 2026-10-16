import { useState } from 'react';
import { toApiError, type ApiClient, type ApiError } from '../api/client.ts';
import type { Issue, IssueStatus } from '../api/types.ts';
import ErrorNotice from './ErrorNotice.tsx';

interface IssueStatusActionsProps {
  issue: Issue;
  client: ApiClient;
  onIssueChange: (issue: Issue) => void;
}

type ActionOutcome = { kind: 'message'; text: string } | { kind: 'error'; error: ApiError };

export default function IssueStatusActions({ issue, client, onIssueChange }: IssueStatusActionsProps) {
  const [pending, setPending] = useState(false);
  const [outcome, setOutcome] = useState<ActionOutcome | null>(null);
  const resolving = issue.status === 'UNRESOLVED';

  async function changeStatus() {
    setPending(true);
    setOutcome(null);
    try {
      const updated = resolving ? await client.resolveIssue(issue.id) : await client.reopenIssue(issue.id);
      onIssueChange(updated);
      setOutcome({ kind: 'message', text: resolving ? 'The issue is resolved.' : 'The issue is reopened.' });
    } catch (error) {
      const apiError = toApiError(error);
      setOutcome(apiError.status === 409 ? await reloadAfterConflict() : { kind: 'error', error: apiError });
    } finally {
      setPending(false);
    }
  }

  async function reloadAfterConflict(): Promise<ActionOutcome> {
    try {
      const current = await client.getIssue(issue.id);
      onIssueChange(current);
      return {
        kind: 'message',
        text: `The issue was changed by another request or a new event. It is now ${describeStatus(current.status)}.`,
      };
    } catch (error) {
      return { kind: 'error', error: toApiError(error) };
    }
  }

  return (
    <div className="issue-actions">
      <button type="button" onClick={changeStatus} disabled={pending}>
        {buttonLabel(resolving, pending)}
      </button>
      <p className="issue-action-message" role="status">
        {outcome?.kind === 'message' ? outcome.text : ''}
      </p>
      {outcome?.kind === 'error' && <ErrorNotice error={outcome.error} />}
    </div>
  );
}

function buttonLabel(resolving: boolean, pending: boolean): string {
  if (resolving) {
    return pending ? 'Resolving...' : 'Resolve issue';
  }
  return pending ? 'Reopening...' : 'Reopen issue';
}

function describeStatus(status: IssueStatus): string {
  return status === 'RESOLVED' ? 'resolved' : 'unresolved';
}
