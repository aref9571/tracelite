import { useCallback, useEffect, useState } from 'react';
import { toApiError, type ApiError } from './api/client.ts';

export type RequestState<T> =
  | { status: 'loading' }
  | { status: 'success'; data: T }
  | { status: 'error'; error: ApiError };

type Loader<T> = (signal: AbortSignal) => Promise<T>;

interface SettledRequest<T> {
  load: Loader<T>;
  attempt: number;
  state: RequestState<T>;
}

export function useApiRequest<T>(load: Loader<T>): [RequestState<T>, () => void] {
  const [attempt, setAttempt] = useState(0);
  const [settled, setSettled] = useState<SettledRequest<T> | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal).then(
      (data) => setSettled({ load, attempt, state: { status: 'success', data } }),
      (error: unknown) => {
        if (!controller.signal.aborted) {
          setSettled({ load, attempt, state: { status: 'error', error: toApiError(error) } });
        }
      },
    );
    return () => controller.abort();
  }, [load, attempt]);

  const retry = useCallback(() => setAttempt((value) => value + 1), []);

  if (settled === null || settled.load !== load || settled.attempt !== attempt) {
    return [{ status: 'loading' }, retry];
  }
  return [settled.state, retry];
}
