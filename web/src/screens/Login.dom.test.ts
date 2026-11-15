// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, OfflineError } from '../lib/api';
import * as router from '../lib/router';
import { logoutPending, setLogoutPending } from '../lib/boot';
import Login from './Login.svelte';
import { button, config, text, type } from './testkit';

vi.mock('../lib/router', async (orig) => ({ ...(await orig<typeof import('../lib/router')>()), leave: vi.fn() }));

let app: ReturnType<typeof mount> | null = null;

afterEach(() => {
  if (app) unmount(app);
  app = null;
  vi.restoreAllMocks();
  vi.mocked(router.leave).mockReset();
  localStorage.clear();
  document.body.innerHTML = '';
});

describe('Login', () => {
  it('sso_error_from_an_invite_keeps_the_invite', () => {
    app = mount(Login, { target: document.body, props: { config: { ...config, oidc: { name: 'Holy Grail', account_url: null } }, invite: 'abc_DEF-1', serverError: 'not_allowed', onSignedIn: () => {} } });
    flushSync();
    expect(text()).toContain('Your account is not allowed on this server.');
    expect(button('Sign in with Holy Grail')!.getAttribute('href')).toBe('/auth/oidc/start?invite=abc_DEF-1');
    expect(button('Create account')).toBeDefined();
    expect(button('Back to the invite')!.getAttribute('href')).toBe('/invite/abc_DEF-1');
  });

  it('sso_link_ends_a_pending_logout_before_leaving', async () => {
    setLogoutPending('https://push.example.com/x', config);
    const order: string[] = [];
    vi.spyOn(api, 'logout').mockImplementation(async () => void order.push('logout'));
    vi.mocked(router.leave).mockImplementation((u) => void order.push(u));
    app = mount(Login, { target: document.body, props: { config: { ...config, oidc: { name: 'Holy Grail', account_url: null } }, invite: null, serverError: null, onSignedIn: () => {} } });
    flushSync();
    button('Sign in with Holy Grail')!.click();
    await vi.waitFor(() => expect(order).toEqual(['logout', '/auth/oidc/start']));
    expect(api.logout).toHaveBeenCalledWith('https://push.example.com/x');
    expect(logoutPending()).toBeNull();
  });

  it('failed_local_sign_in_keeps_the_pending_logout', async () => {
    setLogoutPending(null, config);
    vi.spyOn(api, 'logout').mockRejectedValue(new OfflineError());
    vi.spyOn(api, 'login').mockRejectedValue(new OfflineError());
    app = mount(Login, { target: document.body, props: { config, invite: null, serverError: null, onSignedIn: () => {} } });
    flushSync();
    type('#username', 'anna');
    type('#password', 'correct horse battery');
    document.querySelector<HTMLFormElement>('form')!.requestSubmit();
    await vi.waitFor(() => expect(api.login).toHaveBeenCalled());
    await vi.waitFor(() => expect(logoutPending()).not.toBeNull());
  });
});
