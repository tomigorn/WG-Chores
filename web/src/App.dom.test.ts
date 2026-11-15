// @vitest-environment happy-dom
import { flushSync, mount, tick, unmount } from 'svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App.svelte';
import { api, ApiError, OfflineError } from './lib/api';
import { startSync, stopSync } from './lib/sync';
import { cachedSession, logoutPending, rememberSession, setLogoutPending } from './lib/boot';
import { push } from './lib/push';
import { button, config, dialogs, me, type } from './screens/testkit';

let app: ReturnType<typeof mount> | null = null;

async function seedUserData() {
  vi.spyOn(api, 'complete').mockRejectedValue(new OfflineError());
  const s = startSync(me.id);
  await s.setList(9, { x: 1 });
  await s.done.mark(5, '2026-11-14', null);
}

async function userData() {
  const s = startSync(me.id);
  return { list: await s.getList(9), waiting: (await s.outbox.items()).length };
}

afterEach(async () => {
  await startSync(me.id).discard();
  stopSync();
  if (app) unmount(app);
  app = null;
  vi.restoreAllMocks();
  localStorage.clear();
  document.body.innerHTML = '';
});

describe('App', () => {
  it('unauthenticated_during_use_forgets_cached_session', async () => {
    dialogs();
    history.replaceState(null, '', '/settings');
    vi.spyOn(api, 'config').mockResolvedValue(config);
    vi.spyOn(api, 'me').mockResolvedValue(me);
    vi.spyOn(api, 'pushKey').mockResolvedValue({ key: 'x' });
    app = mount(App, { target: document.body });
    await vi.waitFor(() => expect(cachedSession()).not.toBeNull());
    rememberSession(config, me);
    await seedUserData();
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ error: 'unauthenticated', message: 'x' }), { status: 401, headers: { 'content-type': 'application/json' } }));
    await api.households().catch(() => {});
    await tick();
    flushSync();
    expect(cachedSession()).toBeNull();
    expect(location.pathname).toBe('/login');
    await vi.waitFor(async () => expect(await userData()).toEqual({ list: undefined, waiting: 1 }));
  });

  it('expired_login_at_start_clears_previous_users_lists', async () => {
    dialogs();
    history.replaceState(null, '', '/households/9');
    rememberSession(config, me);
    await seedUserData();
    stopSync();
    vi.spyOn(api, 'config').mockResolvedValue(config);
    vi.spyOn(api, 'me').mockRejectedValue(new ApiError(401, 'unauthenticated', 'x'));
    app = mount(App, { target: document.body });
    await vi.waitFor(() => expect(cachedSession()).toBeNull());
    await vi.waitFor(async () => expect(await userData()).toEqual({ list: undefined, waiting: 1 }));
  });

  it('sign_out_drops_push_and_sends_endpoint_with_logout', async () => {
    dialogs();
    history.replaceState(null, '', '/account');
    vi.spyOn(api, 'config').mockResolvedValue(config);
    vi.spyOn(api, 'me').mockResolvedValue(me);
    const refresh = vi.spyOn(push, 'refresh').mockResolvedValue(undefined);
    const order: string[] = [];
    vi.spyOn(push, 'signOut').mockImplementation(async () => (order.push('push'), 'https://push.example.com/x'));
    const logout = vi.spyOn(api, 'logout').mockImplementation(async () => void order.push('logout'));
    app = mount(App, { target: document.body });
    await vi.waitFor(() => expect(button('Sign out')).toBeDefined());
    expect(refresh).toHaveBeenCalledTimes(1);
    button('Sign out')!.click();
    await vi.waitFor(() => expect(location.pathname).toBe('/login'));
    expect(order).toEqual(['push', 'logout']);
    expect(logout).toHaveBeenCalledWith('https://push.example.com/x');
    expect(logoutPending()).toBeNull();

    vi.spyOn(api, 'login').mockResolvedValue(me);
    await vi.waitFor(() => expect(document.querySelector('#username')).not.toBeNull());
    type('#username', 'anna');
    type('#password', 'correct horse battery');
    document.querySelector<HTMLFormElement>('form')!.requestSubmit();
    await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(2));
  });

  it('offline_sign_out_keeps_login_until_the_server_logout_went_through', async () => {
    dialogs();
    history.replaceState(null, '', '/account');
    vi.spyOn(api, 'config').mockResolvedValue(config);
    const meCall = vi.spyOn(api, 'me').mockResolvedValue(me);
    vi.spyOn(push, 'refresh').mockResolvedValue(undefined);
    vi.spyOn(push, 'signOut').mockResolvedValue('https://push.example.com/x');
    const logout = vi.spyOn(api, 'logout').mockRejectedValue(new OfflineError());
    app = mount(App, { target: document.body });
    await vi.waitFor(() => expect(button('Sign out')).toBeDefined());
    button('Sign out')!.click();
    await vi.waitFor(() => expect(location.pathname).toBe('/login'));
    expect(logoutPending()).toEqual({ endpoint: 'https://push.example.com/x', config });
    unmount(app);
    document.body.innerHTML = '';

    // next start, still offline: login screen, no /me
    meCall.mockClear();
    vi.mocked(api.config).mockRejectedValue(new OfflineError());
    history.replaceState(null, '', '/households/9');
    app = mount(App, { target: document.body });
    await vi.waitFor(() => expect(document.querySelector('#username')).not.toBeNull());
    expect(location.pathname).toBe('/login');
    expect(meCall).not.toHaveBeenCalled();

    // back online: the logout is finished before anything asks for /me
    const order: string[] = [];
    vi.mocked(api.config).mockResolvedValue(config);
    logout.mockImplementation(async () => void order.push('logout'));
    meCall.mockImplementation(async () => {
      order.push('me');
      throw new ApiError(401, 'unauthenticated', 'x');
    });
    dispatchEvent(new Event('online'));
    await vi.waitFor(() => expect(order).toEqual(['logout', 'me']));
    expect(logout).toHaveBeenLastCalledWith('https://push.example.com/x');
    expect(logoutPending()).toBeNull();
    expect(document.querySelector('#username')).not.toBeNull();
  });

  it('pending_logout_is_finished_at_start_before_me', async () => {
    dialogs();
    history.replaceState(null, '', '/households/9');
    setLogoutPending(null, config);
    const order: string[] = [];
    vi.spyOn(api, 'config').mockResolvedValue(config);
    vi.spyOn(api, 'logout').mockImplementation(async () => void order.push('logout'));
    vi.spyOn(api, 'me').mockImplementation(async () => {
      order.push('me');
      throw new ApiError(401, 'unauthenticated', 'x');
    });
    app = mount(App, { target: document.body });
    await vi.waitFor(() => expect(order).toEqual(['logout', 'me']));
    expect(logoutPending()).toBeNull();
  });

  it('a pending logout never ends a fresh local sign-in', async () => {
    dialogs();
    history.replaceState(null, '', '/login');
    setLogoutPending(null, config);
    vi.spyOn(api, 'config').mockResolvedValue(config);
    const order: string[] = [];
    vi.spyOn(api, 'logout').mockRejectedValueOnce(new OfflineError()).mockImplementation(async () => void order.push('logout'));
    vi.spyOn(push, 'refresh').mockResolvedValue(undefined);
    app = mount(App, { target: document.body });
    await vi.waitFor(() => expect(document.querySelector('#username')).not.toBeNull());
    vi.spyOn(api, 'me').mockResolvedValue(me);
    vi.spyOn(api, 'login').mockImplementation(async () => {
      order.push('login');
      // the network comes back while the sign-in is on its way
      dispatchEvent(new Event('online'));
      await new Promise((r) => setTimeout(r, 20));
      order.push('login done');
      return me;
    });
    type('#username', 'anna');
    type('#password', 'correct horse battery');
    document.querySelector<HTMLFormElement>('form')!.requestSubmit();
    await vi.waitFor(() => expect(location.pathname).not.toBe('/login'));
    await new Promise((r) => setTimeout(r, 20));
    expect(order).toEqual(['logout', 'login', 'login done']);
    expect(logoutPending()).toBeNull();
  });
});
