import { Link } from 'react-router';

interface PaginationProps {
  pageNumber: number;
  totalPages: number;
  pageHref: (pageNumber: number) => string;
}

export default function Pagination({ pageNumber, totalPages, pageHref }: PaginationProps) {
  if (totalPages <= 1 && pageNumber <= 1) {
    return null;
  }

  return (
    <nav className="pagination" aria-label="Issue pages">
      {pageNumber > 1 ? (
        <Link to={pageHref(pageNumber - 1)} rel="prev">
          Previous
        </Link>
      ) : (
        <span className="pagination-disabled">Previous</span>
      )}
      <span>
        Page {pageNumber} of {Math.max(totalPages, 1)}
      </span>
      {pageNumber < totalPages ? (
        <Link to={pageHref(pageNumber + 1)} rel="next">
          Next
        </Link>
      ) : (
        <span className="pagination-disabled">Next</span>
      )}
    </nav>
  );
}
