// @vitest-environment happy-dom
import { flushSync, mount, tick, unmount } from 'svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App.svelte';
import { api } from './lib/api';
import { cachedSession, rememberSession } from './lib/boot';
import { config, dialogs, me } from './screens/testkit';

let app: ReturnType<typeof mount> | null = null;

afterEach(() => {
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
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ error: 'unauthenticated', message: 'x' }), { status: 401, headers: { 'content-type': 'application/json' } }));
    await api.households().catch(() => {});
    await tick();
    flushSync();
    expect(cachedSession()).toBeNull();
    expect(location.pathname).toBe('/login');
  });
});
