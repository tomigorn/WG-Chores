// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Invite from './Invite.svelte';
import SnackHost from '../components/SnackHost.svelte';
import { api, ApiError, type Me, type ServerConfig } from '../lib/api';
import * as router from '../lib/router';
import { clearSnack } from '../lib/snack';
import { button, config, fixedTime, me, text, type } from './testkit';

vi.mock('../lib/router', async (orig) => ({ ...(await orig<typeof import('../lib/router')>()), navigate: vi.fn() }));

const TOKEN = 'tok_EN-1'; // leak-check: ok
let apps: ReturnType<typeof mount>[] = [];

async function open(props: { me: Me | null; config?: ServerConfig; onsignedin?: () => Promise<Me | null> }) {
  apps.push(mount(SnackHost, { target: document.body }));
  apps.push(mount(Invite, { target: document.body, props: { invite: TOKEN, config, lang: 'en', onsignedin: async () => null, ...props } }));
  await vi.waitFor(() => expect(text()).toContain('WG Zürich'));
  flushSync();
}

beforeEach(() => {
  fixedTime();
  vi.spyOn(api, 'invitePreview').mockResolvedValue({ household_name: 'WG Zürich', invited_by: 'Rafi', expires_at: '2026-11-20T11:00:00.000Z', valid: true, reason: null, can_register: true });
});

afterEach(() => {
  for (const a of apps) unmount(a);
  apps = [];
  clearSnack();
  vi.restoreAllMocks();
  vi.mocked(router.navigate).mockReset();
  document.body.innerHTML = '';
});

describe('Invite page', () => {
  it('preview_shows_household_inviter_and_expiry', async () => {
    await open({ me: null });
    expect(api.invitePreview).toHaveBeenCalledWith(TOKEN);
    expect(text()).toContain('Rafi invited you to');
    expect(text()).toContain('expires in 6 days');
  });

  it('invite_flow_new_local_user', async () => {
    const register = vi.spyOn(api, 'register').mockResolvedValue({ ...me, id: 9 });
    const onsignedin = vi.fn(async () => ({ ...me, id: 9, last_household_id: 5 }));
    await open({ me: null, onsignedin });
    button('Create account')!.click();
    flushSync();
    type('#display-name', 'Jane Doe');
    type('#username', 'jane');
    type('#password', 'correct horse battery');
    document.querySelector<HTMLFormElement>('form')!.requestSubmit();
    await vi.waitFor(() => expect(register).toHaveBeenCalledWith({ username: 'jane', password: 'correct horse battery', display_name: 'Jane Doe', invite: TOKEN })); // leak-check: ok
    await vi.waitFor(() => expect(router.navigate).toHaveBeenCalledWith('/households/5', { replace: true }));
  });

  it('no_create_account_when_server_says_no', async () => {
    vi.mocked(api.invitePreview).mockResolvedValue({ household_name: 'WG Zürich', invited_by: 'Rafi', expires_at: '2026-11-20T11:00:00.000Z', valid: true, reason: null, can_register: false });
    await open({ me: null });
    expect(button('Create account')).toBeUndefined();
  });

  it('invite_flow_sso_redirect_keeps_token', async () => {
    await open({ me: null, config: { ...config, local_accounts: false, oidc: { name: 'Holy Grail', account_url: null } } });
    const sso = button('Sign in with Holy Grail')!;
    expect(sso.getAttribute('href')).toBe(`/auth/oidc/start?invite=${TOKEN}`);
    expect(document.querySelector('#username')).toBeNull();
  });

  it('signed_in_join_goes_to_household', async () => {
    const accept = vi.spyOn(api, 'acceptInvite').mockResolvedValue({ household_id: 4 });
    await open({ me });
    button('Join WG Zürich')!.click();
    await vi.waitFor(() => expect(accept).toHaveBeenCalledWith(TOKEN));
    await vi.waitFor(() => expect(router.navigate).toHaveBeenCalledWith('/households/4', { replace: true }));
    expect(document.querySelector('.snack')!.textContent).toContain('Welcome to WG Zürich');
  });

  it('already_member_goes_there_with_toast', async () => {
    vi.spyOn(api, 'acceptInvite').mockResolvedValue({ household_id: 4, already_member: true });
    await open({ me });
    button('Join WG Zürich')!.click();
    await vi.waitFor(() => expect(router.navigate).toHaveBeenCalledWith('/households/4', { replace: true }));
    expect(document.querySelector('.snack')!.textContent).toContain('You are already a member of WG Zürich');
  });

  it('invalid_invite_says_why', async () => {
    vi.mocked(api.invitePreview).mockResolvedValue({ household_name: 'WG Zürich', invited_by: 'Rafi', expires_at: '2026-11-10T11:00:00.000Z', valid: false, reason: 'expired', can_register: false });
    await open({ me: null });
    expect(text()).toContain('This invite link has expired.');
    expect(button('Join WG Zürich')).toBeUndefined();
    expect(document.querySelector('#username')).toBeNull();
  });

  it('unknown_invite_says_not_valid', async () => {
    vi.mocked(api.invitePreview).mockRejectedValue(new ApiError(404, 'invite_not_found', ''));
    apps.push(mount(Invite, { target: document.body, props: { invite: TOKEN, config, me: null, lang: 'en', onsignedin: async () => null } }));
    await vi.waitFor(() => expect(text()).toContain('This invite link is not valid.'));
  });
});
