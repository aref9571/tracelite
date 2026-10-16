export const ISSUE_STATUSES = ['UNRESOLVED', 'RESOLVED'] as const;

export type IssueStatus = (typeof ISSUE_STATUSES)[number];

export interface Issue {
  id: string;
  application: string;
  environment: string;
  fingerprint: string;
  exceptionType: string;
  message: string | null;
  status: IssueStatus;
  occurrenceCount: number;
  firstSeenAt: string;
  lastSeenAt: string;
  resolvedAt: string | null;
}

export interface IssueEvent {
  id: string;
  exceptionType: string;
  message: string | null;
  stackTrace: string | null;
  occurredAt: string;
  receivedAt: string;
  correlationId: string | null;
  context: Record<string, unknown> | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface FieldError {
  field: string;
  message: string;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: FieldError[];
}

export interface PageQuery {
  page: number;
  size: number;
}

export interface IssueListQuery extends PageQuery {
  status?: IssueStatus;
  environment?: string;
}
