import type { IssueStatus } from '../api/types.ts';

export default function StatusBadge({ status }: { status: IssueStatus }) {
  return (
    <span className={`status-badge status-${status.toLowerCase()}`}>
      {status === 'RESOLVED' ? 'Resolved' : 'Unresolved'}
    </span>
  );
}
