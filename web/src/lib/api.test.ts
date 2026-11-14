import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, OfflineError, setUnauthenticatedHandler } from './api';

function respond(status: number, body?: unknown, type = 'application/json') {
  return vi.fn(async (_url: string, _init: RequestInit) =>
    new Response(body === undefined ? null : typeof body === 'string' ? body : JSON.stringify(body), {
      status,
      headers: { 'content-type': type },
    }),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  setUnauthenticatedHandler(() => {});
});

describe('api client', () => {
  it('api_sends_csrf_header', async () => {
    const f = respond(200, { id: 1, username: 'anna', display_name: 'Anna', kind: 'local', picture_url: null, account_url: null });
    vi.stubGlobal('fetch', f);
    await api.login('anna', 'correct horse battery');
    const [url, init] = f.mock.calls[0]!;
    expect(url).toBe('/api/v1/auth/login');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('same-origin');
    const h = new Headers(init.headers);
    expect(h.get('X-WGC')).toBe('1');
    expect(h.get('content-type')).toBe('application/json');
    expect(JSON.parse(init.body as string)).toEqual({ username: 'anna', password: 'correct horse battery' }); // leak-check: ok
  });

  it('get_requests_send_csrf_header_too', async () => {
    const f = respond(200, { name: 'WG Chores', local_accounts: true, open_registration: false, oidc: null, notify_times: [], tz: 'UTC', version: '0' });
    vi.stubGlobal('fetch', f);
    const c = await api.config();
    expect(c.local_accounts).toBe(true);
    expect(new Headers(f.mock.calls[0]![1].headers).get('X-WGC')).toBe('1');
  });

  it('unauthenticated_calls_handler', async () => {
    vi.stubGlobal('fetch', respond(401, { error: 'unauthenticated', message: 'x' }));
    const h = vi.fn();
    setUnauthenticatedHandler(h);
    await expect(api.me()).rejects.toBeInstanceOf(ApiError);
    expect(h).toHaveBeenCalledOnce();
  });

  it('wrong_password_is_an_error_not_a_redirect', async () => {
    vi.stubGlobal('fetch', respond(401, { error: 'invalid_credentials', message: 'x' }));
    const h = vi.fn();
    setUnauthenticatedHandler(h);
    await expect(api.login('anna', 'nope')).rejects.toMatchObject({ status: 401, code: 'invalid_credentials' });
    expect(h).not.toHaveBeenCalled();
  });

  it('network_failure_is_offline_error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    await expect(api.me()).rejects.toBeInstanceOf(OfflineError);
  });

  it('proxy_gateway_error_is_offline_error', async () => {
    vi.stubGlobal('fetch', respond(502, '<html>Bad Gateway</html>', 'text/html'));
    await expect(api.me()).rejects.toBeInstanceOf(OfflineError);
  });

  it('logout_accepts_no_content', async () => {
    vi.stubGlobal('fetch', respond(204));
    await expect(api.logout()).resolves.toBeUndefined();
  });

  it('too_many_attempts_keeps_retry_after', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: 'too_many_attempts', message: 'x' }), {
      status: 429, headers: { 'content-type': 'application/json', 'retry-after': '60' },
    })));
    await expect(api.login('a', 'b')).rejects.toMatchObject({ code: 'too_many_attempts', retryAfter: 60 });
  });
});

describe('api request shapes', () => {
  async function call(fn: () => Promise<unknown>, status = 200, body: unknown = {}) {
    const f = respond(status, status === 204 ? undefined : body);
    vi.stubGlobal('fetch', f);
    await fn();
    const [url, init] = f.mock.calls[0]!;
    return {
      url,
      method: init.method,
      csrf: new Headers(init.headers).get('X-WGC'),
      body: init.body === undefined ? undefined : JSON.parse(init.body as string),
    };
  }

  it('households_and_members', async () => {
    expect(await call(() => api.households())).toEqual({ url: '/api/v1/households', method: 'GET', csrf: '1', body: undefined });
    expect(await call(() => api.createHousehold('Flat'), 201)).toMatchObject({ method: 'POST', url: '/api/v1/households', body: { name: 'Flat' } });
    expect(await call(() => api.renameHousehold(4, 'Home'))).toMatchObject({ method: 'PATCH', url: '/api/v1/households/4', body: { name: 'Home' } });
    expect(await call(() => api.leaveHousehold(4, true), 204)).toMatchObject({ method: 'POST', url: '/api/v1/households/4/leave', body: { confirm_delete: true } });
    expect(await call(() => api.leaveHousehold(4), 204)).toMatchObject({ body: undefined });
    expect(await call(() => api.addMember(4, { username: 'anna' }), 201)).toMatchObject({ method: 'POST', url: '/api/v1/households/4/members', body: { username: 'anna' } });
    expect(await call(() => api.removeMember(4, 9), 204)).toMatchObject({ method: 'DELETE', url: '/api/v1/households/4/members/9', csrf: '1' });
    expect(await call(() => api.searchUsers('an na'), 200, [])).toMatchObject({ url: '/api/v1/users?q=an+na' });
  });

  it('invites', async () => {
    expect(await call(() => api.createInvite(4), 201)).toMatchObject({ method: 'POST', url: '/api/v1/households/4/invites' });
    expect(await call(() => api.cancelInvite(4, 2), 204)).toMatchObject({ method: 'DELETE', url: '/api/v1/households/4/invites/2' });
    expect(await call(() => api.invitePreview('a_b-c'))).toMatchObject({ method: 'GET', url: '/api/v1/invites/a_b-c' });
    expect(await call(() => api.acceptInvite('a_b-c'))).toMatchObject({ method: 'POST', url: '/api/v1/invites/a_b-c/accept' });
  });

  it('chores_and_categories', async () => {
    expect(await call(() => api.chores(4, { archived: true }), 200, [])).toMatchObject({ url: '/api/v1/households/4/chores?archived=true' });
    expect(await call(() => api.chores(4), 200, [])).toMatchObject({ url: '/api/v1/households/4/chores' });
    expect(await call(() => api.createChore(4, { name: 'Bins', category: 'Kitchen', interval: '1w' }), 201)).toMatchObject({
      method: 'POST', body: { name: 'Bins', category: 'Kitchen', interval: '1w' },
    });
    expect(await call(() => api.patchChore(7, { interval: null }))).toMatchObject({ method: 'PATCH', url: '/api/v1/chores/7', body: { interval: null } });
    expect(await call(() => api.archiveChore(7))).toMatchObject({ method: 'POST', url: '/api/v1/chores/7/archive' });
    expect(await call(() => api.restoreChore(7))).toMatchObject({ method: 'POST', url: '/api/v1/chores/7/restore' });
    expect(await call(() => api.renameCategory(4, 'Bad', 'Bathroom'), 200, [])).toMatchObject({
      method: 'PATCH', url: '/api/v1/households/4/categories', body: { from: 'Bad', to: 'Bathroom' },
    });
  });

  it('completions', async () => {
    const id = '0b6f3c8e-2a3d-4c4e-9f00-1a2b3c4d5e6f';
    expect(await call(() => api.complete(7, { id, done_on: '2026-03-01', note: 'x' }), 201)).toMatchObject({
      method: 'POST', url: '/api/v1/chores/7/completions', body: { id, done_on: '2026-03-01', note: 'x' },
    });
    expect(await call(() => api.history(7, { before: `2026-03-01,${id}`, limit: 20 }), 200, { items: [], next: null })).toMatchObject({
      url: `/api/v1/chores/7/completions?before=2026-03-01%2C${id}&limit=20`,
    });
    expect(await call(() => api.patchCompletion(id, { note: null }))).toMatchObject({ method: 'PATCH', url: `/api/v1/completions/${id}`, body: { note: null } });
    expect(await call(() => api.deleteCompletion(id), 204)).toMatchObject({ method: 'DELETE', url: `/api/v1/completions/${id}` });
  });

  it('push_and_me', async () => {
    expect(await call(() => api.pushKey(), 200, { key: 'k' })).toMatchObject({ url: '/api/v1/push/key' });
    const sub = { endpoint: 'https://push.example.com/x', keys: { p256dh: 'p', auth: 'a' } };
    expect(await call(() => api.subscribe(sub, 'de'), 204)).toMatchObject({ method: 'POST', url: '/api/v1/push/subscriptions', body: { ...sub, lang: 'de' } });
    expect(await call(() => api.unsubscribe(sub.endpoint), 204)).toMatchObject({ method: 'DELETE', body: { endpoint: sub.endpoint } });
    expect(await call(() => api.patchMe({ notify_due: false }))).toMatchObject({ method: 'PATCH', url: '/api/v1/me', body: { notify_due: false } });
    expect(await call(() => api.deleteMe('anna'), 204)).toMatchObject({ method: 'DELETE', url: '/api/v1/me', body: { confirm_username: 'anna' } });
    expect(await call(() => api.setLastHousehold(4), 204)).toMatchObject({ method: 'PUT', url: '/api/v1/me/last-household', body: { id: 4 } });
  });

  it('html_200_on_json_endpoint_is_an_error', async () => {
    vi.stubGlobal('fetch', respond(200, '<html>login portal</html>', 'text/html'));
    await expect(api.me()).rejects.toMatchObject({ code: 'invalid_response' });
  });
});
