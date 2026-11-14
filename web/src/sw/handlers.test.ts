import { afterEach, describe, expect, it, vi } from 'vitest';
import { NAV_TIMEOUT_MS, registerHandlers, type Scope } from './core';

const ORIGIN = 'https://chores.example.com';
const BUILD = { version: 'v2', files: ['/index.html', '/assets/index-abc.js'] };

function harness(fetchImpl: (r: Request | string) => Promise<Response>) {
  const handlers = new Map<string, (e: unknown) => void>();
  const stores = new Map<string, Map<string, Response>>([['wgc-shell-v1', new Map()], ['other', new Map()]]);
  const open = async (n: string) => {
    if (!stores.has(n)) stores.set(n, new Map());
    const m = stores.get(n)!;
    return {
      addAll: async (rs: RequestInfo[]) => {
        for (const r of rs) {
          const path = new URL(typeof r === 'string' ? r : r.url, ORIGIN).pathname;
          m.set(path, new Response(`cached ${path}`));
        }
      },
      match: async (r: RequestInfo) => m.get(new URL(typeof r === 'string' ? r : r.url, ORIGIN).pathname)?.clone(),
    };
  };
  const client = { url: ORIGIN + '/households/1', focused: false, focus: vi.fn(async () => {}), postMessage: vi.fn() };
  const clients: (typeof client)[] = [client];
  const scope = {
    location: { origin: ORIGIN },
    addEventListener: (t: string, cb: (e: never) => void) => handlers.set(t, cb as (e: unknown) => void),
    skipWaiting: vi.fn(async () => {}),
    fetch: vi.fn(fetchImpl),
    caches: { open, keys: async () => [...stores.keys()], delete: async (n: string) => stores.delete(n) },
    clients: { claim: vi.fn(async () => {}), matchAll: vi.fn(async () => clients), openWindow: vi.fn(async () => null) },
    registration: { showNotification: vi.fn(async () => {}) },
  } satisfies Scope;
  registerHandlers(scope, BUILD);

  async function dispatch(type: string, extra: object): Promise<Response | undefined> {
    const waits: Promise<unknown>[] = [];
    let responded: Promise<Response> | undefined;
    handlers.get(type)!({ waitUntil: (p: Promise<unknown>) => waits.push(p), respondWith: (p: Promise<Response>) => (responded = p), ...extra });
    await Promise.all(waits);
    return responded;
  }
  const navigation = (path: string) => ({ url: ORIGIN + path, mode: 'navigate', method: 'GET' }) as unknown as Request;
  return { scope, stores, dispatch, navigation, client, clients };
}

afterEach(() => vi.useRealTimers());

describe('service worker handlers', () => {
  it('install_precaches_and_activate_drops_old_caches', async () => {
    const h = harness(async () => new Response('net'));
    await h.dispatch('install', {});
    expect([...h.stores.get('wgc-shell-v2')!.keys()]).toEqual(['/index.html', '/assets/index-abc.js']);
    await h.dispatch('activate', {});
    expect([...h.stores.keys()].sort()).toEqual(['other', 'wgc-shell-v2']);
    expect(h.scope.clients.claim).toHaveBeenCalled();
    expect(h.scope.skipWaiting).not.toHaveBeenCalled();
  });

  it('skip_waiting_only_when_the_page_asks', async () => {
    const h = harness(async () => new Response('net'));
    await h.dispatch('message', { data: { type: 'other' } });
    expect(h.scope.skipWaiting).not.toHaveBeenCalled();
    await h.dispatch('message', { data: { type: 'skip-waiting' } });
    expect(h.scope.skipWaiting).toHaveBeenCalledTimes(1);
  });

  it('navigation_falls_back_to_cached_shell_offline', async () => {
    const h = harness(async () => {
      throw new TypeError('Failed to fetch');
    });
    await h.dispatch('install', {});
    const res = await h.dispatch('fetch', { request: h.navigation('/households/3') });
    expect(await res!.text()).toBe('cached /index.html');
  });

  it('navigation_uses_network_when_online', async () => {
    const h = harness(async () => new Response('fresh'));
    await h.dispatch('install', {});
    expect(await (await h.dispatch('fetch', { request: h.navigation('/households/3') }))!.text()).toBe('fresh');
    const down = harness(async () => new Response('bad gateway', { status: 502 }));
    await down.dispatch('install', {});
    expect(await (await down.dispatch('fetch', { request: down.navigation('/') }))!.text()).toBe('cached /index.html');
  });

  it('slow_navigation_times_out_to_shell', async () => {
    vi.useFakeTimers();
    const h = harness(() => new Promise<Response>(() => {}));
    await h.dispatch('install', {});
    const res = h.dispatch('fetch', { request: h.navigation('/') });
    await vi.advanceTimersByTimeAsync(NAV_TIMEOUT_MS + 1);
    expect(await (await res)!.text()).toBe('cached /index.html');
  });

  it('api_and_auth_are_not_touched', async () => {
    const h = harness(async () => new Response('net'));
    expect(await h.dispatch('fetch', { request: { url: ORIGIN + '/api/v1/me', mode: 'cors', method: 'GET' } })).toBeUndefined();
    expect(await h.dispatch('fetch', { request: h.navigation('/auth/oidc/start') })).toBeUndefined();
  });

  it('push_shows_notification', async () => {
    const h = harness(async () => new Response('net'));
    await h.dispatch('push', { data: { json: () => ({ title: 'Clean toilet is due', body: 'Bathroom', url: '/households/3', tag: 'due-3' }) } });
    expect(h.scope.registration.showNotification).toHaveBeenCalledWith('Clean toilet is due', expect.objectContaining({ body: 'Bathroom', tag: 'due-3', data: { url: '/households/3' } }));
    await h.dispatch('push', { data: { json: () => JSON.parse('not json') } });
    expect(h.scope.registration.showNotification).toHaveBeenLastCalledWith('WG Chores', expect.anything());
  });

  it('click_focuses_and_navigates_or_opens', async () => {
    const h = harness(async () => new Response('net'));
    const notification = { close: vi.fn(), data: { url: '/households/3' } };
    await h.dispatch('notificationclick', { notification });
    expect(notification.close).toHaveBeenCalled();
    expect(h.client.focus).toHaveBeenCalled();
    expect(h.client.postMessage).toHaveBeenCalledWith({ type: 'navigate', url: '/households/3' });
    h.clients.length = 0;
    await h.dispatch('notificationclick', { notification });
    expect(h.scope.clients.openWindow).toHaveBeenCalledWith('/households/3');
  });
});
