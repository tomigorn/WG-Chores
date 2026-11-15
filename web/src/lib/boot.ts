import { ApiError, OfflineError, type Me, type ServerConfig } from './api';

export type BootFailure = 'login' | 'error.offline' | 'error.generic';

export function bootFailure(e: unknown): BootFailure {
  if (e instanceof OfflineError) return 'error.offline';
  if (e instanceof ApiError && e.status === 401 && e.code === 'unauthenticated') return 'login';
  return 'error.generic';
}

const KEY = 'wgc.session';

/** Lets a reload without network open the app with the cached lists. */
export function rememberSession(config: ServerConfig, me: Me): void {
  try {
    localStorage.setItem(KEY, JSON.stringify({ config, me }));
  } catch {
    /* storage unavailable */
  }
}

export function cachedSession(): { config: ServerConfig; me: Me } | null {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? 'null') as { config?: ServerConfig; me?: Me } | null;
    return v?.config && v.me ? { config: v.config, me: v.me } : null;
  } catch {
    return null;
  }
}

export function forgetSession(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable */
  }
}

const LOGOUT = 'wgc.logout-pending';

/** Sign-out whose server call has not gone through yet; finished before anything else at the next start. */
export function setLogoutPending(endpoint: string | null, config: ServerConfig): void {
  try {
    localStorage.setItem(LOGOUT, JSON.stringify({ endpoint, config }));
  } catch {
    /* storage unavailable */
  }
}

export function logoutPending(): { endpoint: string | null; config: ServerConfig } | null {
  try {
    const v = JSON.parse(localStorage.getItem(LOGOUT) ?? 'null') as { endpoint?: string | null; config?: ServerConfig } | null;
    return v?.config ? { endpoint: v.endpoint ?? null, config: v.config } : null;
  } catch {
    return null;
  }
}

export function clearLogoutPending(): void {
  try {
    localStorage.removeItem(LOGOUT);
  } catch {
    /* storage unavailable */
  }
}
