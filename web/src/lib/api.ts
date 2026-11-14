export interface OidcInfo {
  name: string;
  account_url: string | null;
}

export interface ServerConfig {
  name: string;
  local_accounts: boolean;
  open_registration: boolean;
  oidc: OidcInfo | null;
  notify_times: string[];
  tz: string;
  version: string;
}

export type AccountKind = 'local' | 'sso';

export interface User {
  id: number;
  username: string | null;
  display_name: string;
  kind: AccountKind;
  picture_url: string | null;
  account_url: string | null;
}

export interface Me extends User {
  last_household_id: number | null;
  notify_due: boolean;
  notify_done: boolean;
}

export interface RegisterRequest {
  username: string;
  password: string; // leak-check: ok
  display_name: string;
  invite?: string;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryAfter: number | null = null,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export class OfflineError extends Error {
  constructor() {
    super('offline');
    this.name = 'OfflineError';
  }
}

let onUnauthenticated: () => void = () => {};

export function setUnauthenticatedHandler(h: () => void): void {
  onUnauthenticated = h;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'X-WGC': '1', Accept: 'application/json' };
  if (body !== undefined) headers['content-type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new OfflineError();
  }
  const isJson = (res.headers.get('content-type') ?? '').includes('application/json');
  if (res.ok) {
    if (res.status === 204 || !isJson) return undefined as T;
    return (await res.json()) as T;
  }
  // a gateway error without our JSON means the server is restarting or unreachable
  if (!isJson && [502, 503, 504].includes(res.status)) throw new OfflineError();
  let code = 'http_' + res.status;
  let message = res.statusText;
  if (isJson) {
    const e = (await res.json().catch(() => ({}))) as { error?: string; message?: string };
    code = e.error ?? code;
    message = e.message ?? message;
  }
  const ra = Number(res.headers.get('retry-after'));
  const err = new ApiError(res.status, code, message, Number.isFinite(ra) && ra > 0 ? ra : null);
  if (res.status === 401 && code === 'unauthenticated') onUnauthenticated();
  throw err;
}

export const api = {
  config: () => request<ServerConfig>('GET', '/api/v1/config'),
  login: (username: string, password: string) => request<User>('POST', '/api/v1/auth/login', { username, password }),
  register: (r: RegisterRequest) => request<User>('POST', '/api/v1/auth/register', r),
  logout: () => request<void>('POST', '/api/v1/auth/logout'),
  me: () => request<Me>('GET', '/api/v1/me'),
};

export function ssoStartUrl(invite?: string | null): string {
  return invite ? `/auth/oidc/start?invite=${encodeURIComponent(invite)}` : '/auth/oidc/start';
}
