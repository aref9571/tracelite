const STORAGE_KEY = 'tracelite.managementKey';
const API_KEY_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;

export function isWellFormedApiKey(value: string): boolean {
  return API_KEY_PATTERN.test(value);
}

export function loadStoredApiKey(): string | null {
  try {
    const stored = window.sessionStorage.getItem(STORAGE_KEY);
    return stored !== null && isWellFormedApiKey(stored) ? stored : null;
  } catch {
    return null;
  }
}

export function storeApiKey(apiKey: string): boolean {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, apiKey);
    return true;
  } catch {
    return false;
  }
}

export function clearStoredApiKey(): boolean {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY);
    return true;
  } catch {
    return false;
  }
}
