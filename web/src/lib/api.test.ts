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
