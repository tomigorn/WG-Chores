import type { DueLevel, IntervalCode } from './due';

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

export interface Household {
  id: number;
  name: string;
  member_count: number;
}

export interface Member {
  id: number;
  display_name: string;
  username: string | null;
  picture_url: string | null;
  joined_at: string;
}

export interface UserHit {
  id: number;
  display_name: string;
  username: string | null;
  picture_url: string | null;
}

export interface By {
  id: number;
  display_name: string;
  former: boolean;
}

export interface Chore {
  id: number;
  household_id: number;
  name: string;
  category: string | null;
  interval: IntervalCode | null;
  archived: boolean;
  last: { done_on: string; by: By } | null;
  days_since: number | null;
  due_level: DueLevel;
  days_overdue: number;
  usual_interval: number | null;
}

export interface ChoreInput {
  name: string;
  category?: string | null;
  interval?: IntervalCode | null;
}

export interface Category {
  name: string;
  chores: number;
}

export interface Completion {
  id: string;
  done_on: string;
  note: string | null;
  by: By;
  created_at: string;
}

export interface CompletionPage {
  items: Completion[];
  next: string | null;
}

export interface Invite {
  id: number;
  created_by: { id: number; display_name: string };
  created_at: string;
  expires_at: string;
}

export interface NewInvite {
  id: number;
  url: string;
  expires_at: string;
}

export interface InvitePreview {
  household_name: string;
  invited_by: string;
  expires_at: string;
  valid: boolean;
  reason: 'used' | 'expired' | 'cancelled' | null;
  can_register: boolean;
}

export interface InviteAccepted {
  household_id: number;
  already_member?: boolean;
}

export interface PushSubscriptionInput {
  endpoint: string;
  keys: { p256dh: string; auth: string };
  expirationTime?: number | null;
}

export interface MePatch {
  display_name?: string;
  current_password?: string;
  new_password?: string;
  notify_due?: boolean;
  notify_done?: boolean;
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

/** A hung connection (e.g. after phone sleep) counts as offline after this long. */
export const REQUEST_TIMEOUT_MS = 15_000;

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await send<T>(method, path, body, ctrl.signal);
  } finally {
    clearTimeout(timer);
  }
}

async function send<T>(method: string, path: string, body: unknown, signal: AbortSignal): Promise<T> {
  const headers: Record<string, string> = { 'X-WGC': '1', Accept: 'application/json' };
  if (body !== undefined) headers['content-type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    });
  } catch {
    throw new OfflineError();
  }
  const isJson = (res.headers.get('content-type') ?? '').includes('application/json');
  if (res.ok) {
    if (res.status === 204) return undefined as T;
    // e.g. a proxy's login or error page answered instead of the server
    if (!isJson) throw new ApiError(res.status, 'invalid_response', 'Unexpected response.');
    try {
      return (await res.json()) as T;
    } catch (e) {
      if (signal.aborted) throw new OfflineError();
      throw e;
    }
  }
  // a gateway error without our JSON means the server is restarting or unreachable
  if (!isJson && [502, 503, 504].includes(res.status)) throw new OfflineError();
  let code = 'http_' + res.status;
  let message = res.statusText;
  if (isJson) {
    const e = (await res.json().catch(() => ({}))) as { error?: string; message?: string };
    if (signal.aborted) throw new OfflineError();
    code = e.error ?? code;
    message = e.message ?? message;
  }
  const ra = Number(res.headers.get('retry-after'));
  const err = new ApiError(res.status, code, message, Number.isFinite(ra) && ra > 0 ? ra : null);
  if (res.status === 401 && code === 'unauthenticated') onUnauthenticated();
  throw err;
}

const id = (v: number | string) => encodeURIComponent(String(v));

function query(params: Record<string, string | number | boolean | undefined>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined) q.set(k, String(v));
  const s = q.toString();
  return s ? `?${s}` : '';
}

const H = '/api/v1/households';

export const api = {
  config: () => request<ServerConfig>('GET', '/api/v1/config'),
  login: (username: string, password: string) => request<User>('POST', '/api/v1/auth/login', { username, password }),
  register: (r: RegisterRequest) => request<User>('POST', '/api/v1/auth/register', r),
  logout: (pushEndpoint?: string | null) => request<void>('POST', '/api/v1/auth/logout', pushEndpoint ? { push_endpoint: pushEndpoint } : undefined),

  me: () => request<Me>('GET', '/api/v1/me'),
  patchMe: (p: MePatch) => request<Me>('PATCH', '/api/v1/me', p),
  /** Local accounts confirm with `current_password`, SSO accounts with their display name. */
  deleteMe: (c: { current_password: string } | { confirm_username: string }) => request<void>('DELETE', '/api/v1/me', c), // leak-check: ok
  setLastHousehold: (householdId: number) => request<void>('PUT', '/api/v1/me/last-household', { id: householdId }),

  households: () => request<Household[]>('GET', H),
  createHousehold: (name: string) => request<Household>('POST', H, { name }),
  household: (hid: number) => request<Household>('GET', `${H}/${id(hid)}`),
  renameHousehold: (hid: number, name: string) => request<Household>('PATCH', `${H}/${id(hid)}`, { name }),
  leaveHousehold: (hid: number, confirmDelete = false) =>
    request<void>('POST', `${H}/${id(hid)}/leave`, confirmDelete ? { confirm_delete: true } : undefined),
  members: (hid: number) => request<Member[]>('GET', `${H}/${id(hid)}/members`),
  addMember: (hid: number, who: { username: string } | { user_id: number }) => request<Member>('POST', `${H}/${id(hid)}/members`, who),
  removeMember: (hid: number, userId: number, confirmDelete = false) =>
    request<void>('DELETE', `${H}/${id(hid)}/members/${id(userId)}`, confirmDelete ? { confirm_delete: true } : undefined),
  searchUsers: (q: string) => request<UserHit[]>('GET', `/api/v1/users${query({ q })}`),

  invites: (hid: number) => request<Invite[]>('GET', `${H}/${id(hid)}/invites`),
  createInvite: (hid: number) => request<NewInvite>('POST', `${H}/${id(hid)}/invites`),
  cancelInvite: (hid: number, inviteId: number) => request<void>('DELETE', `${H}/${id(hid)}/invites/${id(inviteId)}`),
  invitePreview: (t: string) => request<InvitePreview>('GET', `/api/v1/invites/${id(t)}`),
  acceptInvite: (t: string) => request<InviteAccepted>('POST', `/api/v1/invites/${id(t)}/accept`),

  chores: (hid: number, o: { archived?: boolean } = {}) =>
    request<Chore[]>('GET', `${H}/${id(hid)}/chores${query({ archived: o.archived || undefined })}`),
  createChore: (hid: number, c: ChoreInput) => request<Chore>('POST', `${H}/${id(hid)}/chores`, c),
  chore: (choreId: number) => request<Chore>('GET', `/api/v1/chores/${id(choreId)}`),
  patchChore: (choreId: number, c: Partial<ChoreInput>) => request<Chore>('PATCH', `/api/v1/chores/${id(choreId)}`, c),
  archiveChore: (choreId: number) => request<Chore>('POST', `/api/v1/chores/${id(choreId)}/archive`),
  restoreChore: (choreId: number) => request<Chore>('POST', `/api/v1/chores/${id(choreId)}/restore`),
  categories: (hid: number) => request<Category[]>('GET', `${H}/${id(hid)}/categories`),
  renameCategory: (hid: number, from: string, to: string) => request<Category[]>('PATCH', `${H}/${id(hid)}/categories`, { from, to }),

  complete: (choreId: number, c: { id: string; done_on?: string; note?: string | null }) =>
    request<Completion>('POST', `/api/v1/chores/${id(choreId)}/completions`, c),
  history: (choreId: number, o: { before?: string; limit?: number } = {}) =>
    request<CompletionPage>('GET', `/api/v1/chores/${id(choreId)}/completions${query(o)}`),
  patchCompletion: (cid: string, p: { done_on?: string; note?: string | null }) => request<Completion>('PATCH', `/api/v1/completions/${id(cid)}`, p),
  deleteCompletion: (cid: string) => request<void>('DELETE', `/api/v1/completions/${id(cid)}`),

  pushKey: () => request<{ key: string }>('GET', '/api/v1/push/key'),
  subscribe: (sub: PushSubscriptionInput, lang: 'en' | 'de') => request<void>('POST', '/api/v1/push/subscriptions', { ...sub, lang }),
  unsubscribe: (endpoint: string) => request<void>('DELETE', '/api/v1/push/subscriptions', { endpoint }),
};

export function ssoStartUrl(invite?: string | null): string {
  return invite ? `/auth/oidc/start?invite=${encodeURIComponent(invite)}` : '/auth/oidc/start';
}
