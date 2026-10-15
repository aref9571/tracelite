import { useState, type FormEvent } from 'react';
import { ISSUE_STATUSES, type IssueStatus } from '../api/types.ts';
import { isValidEnvironment, type IssueListFilters } from '../issueListSearch.ts';

interface IssueFiltersProps {
  initialFilters: IssueListFilters;
  onApply: (filters: IssueListFilters) => void;
}

const STATUS_LABELS: Record<IssueStatus, string> = {
  UNRESOLVED: 'Unresolved',
  RESOLVED: 'Resolved',
};

export default function IssueFilters({ initialFilters, onApply }: IssueFiltersProps) {
  const [status, setStatus] = useState<IssueStatus | ''>(initialFilters.status ?? '');
  const [environment, setEnvironment] = useState(initialFilters.environment ?? '');
  const [environmentError, setEnvironmentError] = useState(false);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedEnvironment = environment.trim();
    if (trimmedEnvironment !== '' && !isValidEnvironment(trimmedEnvironment)) {
      setEnvironmentError(true);
      return;
    }
    setEnvironmentError(false);
    onApply({
      status: status === '' ? undefined : status,
      environment: trimmedEnvironment === '' ? undefined : trimmedEnvironment,
    });
  }

  function handleClear() {
    setStatus('');
    setEnvironment('');
    setEnvironmentError(false);
    onApply({});
  }

  return (
    <form className="filters" onSubmit={handleSubmit} aria-label="Issue filters">
      <div className="filter-field">
        <label htmlFor="filter-status">Status</label>
        <select
          id="filter-status"
          value={status}
          onChange={(event) => setStatus(event.target.value as IssueStatus | '')}
        >
          <option value="">Any status</option>
          {ISSUE_STATUSES.map((value) => (
            <option key={value} value={value}>
              {STATUS_LABELS[value]}
            </option>
          ))}
        </select>
      </div>
      <div className="filter-field">
        <label htmlFor="filter-environment">Environment</label>
        <input
          id="filter-environment"
          type="text"
          placeholder="Any environment"
          value={environment}
          maxLength={50}
          onChange={(event) => setEnvironment(event.target.value)}
          aria-invalid={environmentError}
          aria-describedby={environmentError ? 'filter-environment-error' : undefined}
        />
        {environmentError && (
          <p id="filter-environment-error" className="field-error">
            Use 1 to 50 letters, digits, dots, underscores, colons or hyphens.
          </p>
        )}
      </div>
      <div className="filter-actions">
        <button type="submit">Apply</button>
        <button type="button" className="button-secondary" onClick={handleClear}>
          Clear
        </button>
      </div>
    </form>
  );
}
