import { Link } from 'react-router';

export default function NotFoundPage() {
  return (
    <section className="notice">
      <h1>Page not found</h1>
      <p>
        <Link to="/">Go to the issue list</Link>
      </p>
    </section>
  );
}
