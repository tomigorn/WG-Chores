// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Account from './Account.svelte';
import { api, ApiError, type Me } from '../lib/api';
import { clearSnack } from '../lib/snack';
import { button, config, dialogs, me, text, type } from './testkit';

let app: ReturnType<typeof mount> | null = null;
const sso: Me = { ...me, kind: 'sso', display_name: 'Jane Doe', username: 'jane', picture_url: 'https://id.example.com/p.png', account_url: 'https://id.example.com/account' };

function open(who: Me, extra: Record<string, unknown> = {}) {
  const props = { config: { ...config, oidc: { name: 'Holy Grail', account_url: 'https://id.example.com/account' } }, me: who, lang: 'en' as const, onlogout: vi.fn(), onme: vi.fn(), ondeleted: vi.fn(), ...extra };
  app = mount(Account, { target: document.body, props });
  flushSync();
  return props;
}

beforeEach(() => dialogs());

afterEach(() => {
  if (app) unmount(app);
  app = null;
  clearSnack();
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('Account', () => {
  it('delete_account_requires_typed_username', async () => {
    const del = vi.spyOn(api, 'deleteMe').mockResolvedValue(undefined);
    const p = open(me);
    button('Delete account')!.click();
    flushSync();
    expect(text()).toContain('Type your username anna to confirm');
    const submit = document.querySelector<HTMLButtonElement>('dialog[open] button[type=submit]')!;
    expect(submit.disabled).toBe(true);
    type('#delete-confirm', 'Anna B');
    expect(submit.disabled).toBe(true);
    type('#delete-confirm', 'anna');
    expect(submit.disabled).toBe(false);
    submit.click();
    await vi.waitFor(() => expect(del).toHaveBeenCalledWith('anna'));
    await vi.waitFor(() => expect(p.ondeleted).toHaveBeenCalled());
  });

  it('sso_user_types_display_name_and_sees_manage_link', async () => {
    const del = vi.spyOn(api, 'deleteMe').mockResolvedValue(undefined);
    open(sso);
    const link = button('Manage your account')!;
    expect(link.getAttribute('href')).toBe('https://id.example.com/account');
    expect(link.getAttribute('rel')).toContain('noopener');
    expect(link.getAttribute('target')).toBe('_blank');
    expect(document.querySelector('#account-name')).toBeNull();
    expect(document.querySelector('#pw-new')).toBeNull();
    expect(document.querySelector<HTMLImageElement>('img')!.src).toBe('https://id.example.com/p.png');
    expect(text()).toContain('@jane');
    button('Delete account')!.click();
    flushSync();
    expect(text()).toContain('Type your name Jane Doe to confirm');
    type('#delete-confirm', 'Jane Doe');
    document.querySelector<HTMLFormElement>('dialog[open] form')!.requestSubmit();
    await vi.waitFor(() => expect(del).toHaveBeenCalledWith('Jane Doe'));
  });

  it('local_user_changes_name', async () => {
    const patch = vi.spyOn(api, 'patchMe').mockResolvedValue({ ...me, display_name: 'Anna Bee' });
    const p = open(me);
    type('#account-name', 'Anna Bee ');
    document.querySelector<HTMLFormElement>('.name-form')!.requestSubmit();
    await vi.waitFor(() => expect(patch).toHaveBeenCalledWith({ display_name: 'Anna Bee' }));
    await vi.waitFor(() => expect(p.onme).toHaveBeenCalledWith(expect.objectContaining({ display_name: 'Anna Bee' })));
  });

  it('password_change_checks_repeat_and_reports_wrong_current', async () => {
    const patch = vi.spyOn(api, 'patchMe').mockRejectedValue(new ApiError(403, 'wrong_password', ''));
    open(me);
    type('#pw-current', 'old password 123');
    type('#pw-new', 'correct horse battery');
    type('#pw-repeat', 'correct horse batterz');
    document.querySelector<HTMLFormElement>('.pw-form')!.requestSubmit();
    flushSync();
    expect(text()).toContain('The new passwords do not match.');
    expect(patch).not.toHaveBeenCalled();
    type('#pw-repeat', 'correct horse battery');
    document.querySelector<HTMLFormElement>('.pw-form')!.requestSubmit();
    await vi.waitFor(() => expect(text()).toContain('The current password is wrong.'));
    expect(patch).toHaveBeenCalledWith({ current_password: 'old password 123', new_password: 'correct horse battery' }); // leak-check: ok
  });

  it('log_out_button', () => {
    const p = open(me);
    button('Sign out')!.click();
    expect(p.onlogout).toHaveBeenCalled();
  });
});
