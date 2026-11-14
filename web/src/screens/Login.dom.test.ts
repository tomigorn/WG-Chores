// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, describe, expect, it } from 'vitest';
import Login from './Login.svelte';
import { button, config, text } from './testkit';

let app: ReturnType<typeof mount> | null = null;

afterEach(() => {
  if (app) unmount(app);
  app = null;
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
});
