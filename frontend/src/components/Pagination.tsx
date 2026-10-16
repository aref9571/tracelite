import { Link } from 'react-router';

interface PaginationProps {
  pageNumber: number;
  totalPages: number;
  pageHref: (pageNumber: number) => string;
  label?: string;
  linkState?: unknown;
}

export default function Pagination({ pageNumber, totalPages, pageHref, label = 'Issue pages', linkState }: PaginationProps) {
  if (totalPages <= 1 && pageNumber <= 1) {
    return null;
  }

  return (
    <nav className="pagination" aria-label={label}>
      {pageNumber > 1 ? (
        <Link to={pageHref(pageNumber - 1)} state={linkState} rel="prev">
          Previous
        </Link>
      ) : (
        <span className="pagination-disabled">Previous</span>
      )}
      <span>
        Page {pageNumber} of {Math.max(totalPages, 1)}
      </span>
      {pageNumber < totalPages ? (
        <Link to={pageHref(pageNumber + 1)} state={linkState} rel="next">
          Next
        </Link>
      ) : (
        <span className="pagination-disabled">Next</span>
      )}
    </nav>
  );
}
