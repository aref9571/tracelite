import type { ApiError } from '../api/client.ts';
import { apiBaseUrl } from '../api/config.ts';

interface ErrorNoticeProps {
  error: ApiError;
  onRetry?: () => void;
}

export default function ErrorNotice({ error, onRetry }: ErrorNoticeProps) {
  const fieldErrors = error.problem?.errors ?? [];

  return (
    <div className="notice notice-error" role="alert">
      <p className="notice-title">{describeError(error)}</p>
      {fieldErrors.length > 0 && (
        <ul>
          {fieldErrors.map((fieldError) => (
            <li key={`${fieldError.field}:${fieldError.message}`}>
              <code>{fieldError.field}</code> {fieldError.message}
            </li>
          ))}
        </ul>
      )}
      {onRetry && (
        <button type="button" className="button-secondary" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

function describeError(error: ApiError): string {
  switch (error.kind) {
    case 'network':
      return `The TraceLite backend at ${apiBaseUrl} could not be reached. Check that it is running and that TRACELITE_SECURITY_ALLOWEDORIGINS includes ${window.location.origin}.`;
    case 'invalid-response':
      return 'The backend sent a response the dashboard could not read.';
    case 'unexpected':
      return 'Something went wrong in the dashboard. Reload the page and try again.';
    case 'http':
      return describeHttpError(error);
  }
}

function describeHttpError(error: ApiError): string {
  if (error.status === 403) {
    return 'This key may not read issues. Use the management key, not an ingestion key.';
  }
  const detail = error.problem?.detail;
  if (error.status !== null && error.status >= 500) {
    return `The backend could not complete the request (status ${error.status}).${detail ? ` ${detail}` : ''}`;
  }
  return detail ?? `The request failed with status ${error.status}.`;
}
