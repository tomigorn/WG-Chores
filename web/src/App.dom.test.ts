// @vitest-environment happy-dom
import { flushSync, mount, tick, unmount } from 'svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App.svelte';
import { api, ApiError, OfflineError } from './lib/api';
import { startSync, stopSync } from './lib/sync';
import { cachedSession, rememberSession } from './lib/boot';
import { config, dialogs, me } from './screens/testkit';

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
});
