import { useState, type FormEvent } from 'react';
import { isWellFormedApiKey } from '../api/apiKey.ts';
import { apiBaseUrl } from '../api/config.ts';

interface ApiKeyFormProps {
  keyRejected: boolean;
  onSubmit: (apiKey: string) => void;
}

export default function ApiKeyForm({ keyRejected, onSubmit }: ApiKeyFormProps) {
  const [value, setValue] = useState('');
  const [formatError, setFormatError] = useState(false);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const apiKey = value.trim();
    if (!isWellFormedApiKey(apiKey)) {
      setFormatError(true);
      return;
    }
    setFormatError(false);
    onSubmit(apiKey);
  }

  return (
    <section className="panel key-form">
      <h1>Connect to TraceLite</h1>
      <p>
        Enter the management key configured on the backend in <code>TRACELITE_SECURITY_MANAGEMENTKEY</code>.
      </p>
      {keyRejected && (
        <p className="notice notice-error" role="alert">
          The backend rejected the key. Check it and try again.
        </p>
      )}
      <form onSubmit={handleSubmit}>
        <label htmlFor="management-key">Management key</label>
        <input
          id="management-key"
          type="password"
          autoComplete="off"
          spellCheck={false}
          value={value}
          onChange={(event) => setValue(event.target.value)}
          aria-invalid={formatError}
          aria-describedby="management-key-hint"
        />
        <p id="management-key-hint" className={formatError ? 'field-error' : 'field-hint'}>
          32 to 128 letters, digits, underscores or hyphens.
        </p>
        <button type="submit">Connect</button>
      </form>
      <p className="field-hint">
        The key is kept in this browser tab only (session storage) and is sent only to {apiBaseUrl}.
      </p>
    </section>
  );
}
