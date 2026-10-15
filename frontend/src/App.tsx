import { useCallback, useMemo, useState } from 'react';
import { Link, Route, Routes } from 'react-router';
import { clearStoredApiKey, loadStoredApiKey, storeApiKey } from './api/apiKey.ts';
import { createApiClient } from './api/client.ts';
import { apiBaseUrl } from './api/config.ts';
import ApiKeyForm from './components/ApiKeyForm.tsx';
import IssueDetailPage from './pages/IssueDetailPage.tsx';
import IssueListPage from './pages/IssueListPage.tsx';
import NotFoundPage from './pages/NotFoundPage.tsx';

export default function App() {
  const [apiKey, setApiKey] = useState<string | null>(loadStoredApiKey);
  const [keyRejected, setKeyRejected] = useState(false);

  const handleKeySubmit = useCallback((submittedKey: string) => {
    storeApiKey(submittedKey);
    setKeyRejected(false);
    setApiKey(submittedKey);
  }, []);

  const handleSignOut = useCallback(() => {
    clearStoredApiKey();
    setKeyRejected(false);
    setApiKey(null);
  }, []);

  const handleUnauthorized = useCallback(() => {
    clearStoredApiKey();
    setKeyRejected(true);
    setApiKey(null);
  }, []);

  const client = useMemo(
    () => (apiKey === null ? null : createApiClient(apiBaseUrl, apiKey, handleUnauthorized)),
    [apiKey, handleUnauthorized],
  );

  return (
    <div className="app">
      <header className="app-header">
        <Link to="/" className="app-title">
          TraceLite
        </Link>
        <span className="app-backend">Backend: {apiBaseUrl}</span>
        {client !== null && (
          <button type="button" className="button-secondary" onClick={handleSignOut}>
            Change key
          </button>
        )}
      </header>
      <main className="app-content">
        {client === null ? (
          <ApiKeyForm keyRejected={keyRejected} onSubmit={handleKeySubmit} />
        ) : (
          <Routes>
            <Route path="/" element={<IssueListPage client={client} />} />
            <Route path="/issues/:issueId" element={<IssueDetailPage client={client} />} />
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        )}
      </main>
    </div>
  );
}
