// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import Settings from './Settings.svelte';
import { api, OfflineError } from '../lib/api';
import { push } from '../lib/push';
import * as i18n from '../lib/i18n';
import { clearSnack } from '../lib/snack';
import { me, text } from './testkit';

let app: ReturnType<typeof mount> | null = null;

function open(over: Partial<typeof me> = {}) {
  const props = { me: { ...me, ...over }, lang: 'en' as const, theme: 'system' as const, ontheme: vi.fn(), onme: vi.fn() };
  app = mount(Settings, { target: document.body, props });
  flushSync();
  return props;
}

const radio = (group: string, label: string) => [...document.querySelectorAll<HTMLElement>(`[aria-label="${group}"] [role=radio]`)].find((r) => r.textContent?.trim() === label)!;

afterEach(() => {
  if (app) unmount(app);
  app = null;
  clearSnack();
  vi.restoreAllMocks();
  i18n.setLanguage('system');
  document.body.innerHTML = '';
});

describe('Settings', () => {
  it('theme_and_language_choices', () => {
    const p = open();
    expect(radio('Theme', 'System').getAttribute('aria-checked')).toBe('true');
    radio('Theme', 'Dark').click();
    expect(p.ontheme).toHaveBeenCalledWith('dark');
    expect([...document.querySelectorAll('[aria-label="Language"] [role=radio]')].map((r) => r.textContent!.trim())).toEqual(['System', 'English', 'Deutsch']);
    radio('Language', 'Deutsch').click();
    expect(i18n.languageSetting()).toBe('de');
  });

  it('notification_toggles_persist', async () => {
    const patch = vi.spyOn(api, 'patchMe').mockResolvedValue({ ...me, notify_done: false });
    const p = open();
    expect(text()).toContain('Notifications arrive once the app is installed.');
    const done = document.querySelector<HTMLInputElement>('#notify-done')!;
    expect(done.getAttribute('role')).toBe('switch');
    expect(done.checked).toBe(true);
    done.click();
    await vi.waitFor(() => expect(patch).toHaveBeenCalledWith({ notify_done: false }));
    await vi.waitFor(() => expect(p.onme).toHaveBeenCalledWith(expect.objectContaining({ notify_done: false })));
  });

  it('toggle_reverts_offline', async () => {
    vi.spyOn(api, 'patchMe').mockRejectedValue(new OfflineError());
    open();
    const due = document.querySelector<HTMLInputElement>('#notify-due')!;
    due.click();
    await vi.waitFor(() => expect(due.checked).toBe(true));
  });

  it('toggle_on_subscribes_first', async () => {
    vi.spyOn(push, 'status').mockReturnValue('ready');
    const order: string[] = [];
    vi.spyOn(push, 'enable').mockImplementation(async () => (order.push('enable'), 'subscribed'));
    vi.spyOn(api, 'patchMe').mockImplementation(async (p) => (order.push('patch'), { ...me, ...p }));
    open({ notify_due: false });
    document.querySelector<HTMLInputElement>('#notify-due')!.click();
    await vi.waitFor(() => expect(order).toEqual(['enable', 'patch']));
    expect(push.enable).toHaveBeenCalledWith('en');
  });

  it('blocked_permission_keeps_toggle_off', async () => {
    vi.spyOn(push, 'status').mockReturnValue('ready');
    vi.spyOn(push, 'enable').mockResolvedValue('blocked');
    const patch = vi.spyOn(api, 'patchMe');
    open({ notify_due: false });
    const due = document.querySelector<HTMLInputElement>('#notify-due')!;
    due.click();
    await vi.waitFor(() => expect(text()).toContain('Blocked in browser settings'));
    expect(due.checked).toBe(false);
    expect(patch).not.toHaveBeenCalled();
  });

  it('blocked_shows_account_state_and_allows_off', async () => {
    vi.spyOn(push, 'status').mockReturnValue('blocked');
    const enable = vi.spyOn(push, 'enable');
    const patch = vi.spyOn(api, 'patchMe').mockResolvedValue({ ...me, notify_done: false });
    open();
    const done = document.querySelector<HTMLInputElement>('#notify-done')!;
    expect(done.checked).toBe(true);
    expect(done.disabled).toBe(false);
    expect(text()).toContain('Blocked in browser settings on this device');
    done.click();
    await vi.waitFor(() => expect(patch).toHaveBeenCalledWith({ notify_done: false }));
    expect(enable).not.toHaveBeenCalled();
    expect(done.checked).toBe(false);
  });

  it('dismissed_prompt_explains', async () => {
    vi.spyOn(push, 'status').mockReturnValue('ready');
    vi.spyOn(push, 'enable').mockResolvedValue('dismissed');
    const patch = vi.spyOn(api, 'patchMe');
    open({ notify_due: false });
    const due = document.querySelector<HTMLInputElement>('#notify-due')!;
    due.click();
    await vi.waitFor(() => expect(text()).toContain('Notifications were not allowed'));
    expect(due.checked).toBe(false);
    expect(patch).not.toHaveBeenCalled();
  });

  it('both_off_unsubscribes', async () => {
    vi.spyOn(push, 'status').mockReturnValue('ready');
    const disable = vi.spyOn(push, 'disable').mockResolvedValue();
    vi.spyOn(api, 'patchMe').mockImplementation(async (p) => ({ ...me, notify_done: false, ...p }));
    open({ notify_done: false });
    document.querySelector<HTMLInputElement>('#notify-due')!.click();
    await vi.waitFor(() => expect(disable).toHaveBeenCalledTimes(1));
  });

  it('one_off_keeps_subscription', async () => {
    vi.spyOn(push, 'status').mockReturnValue('ready');
    const disable = vi.spyOn(push, 'disable').mockResolvedValue();
    const patch = vi.spyOn(api, 'patchMe').mockImplementation(async (p) => ({ ...me, ...p }));
    open({ notify_done: true, notify_due: true });
    document.querySelector<HTMLInputElement>('#notify-due')!.click();
    await vi.waitFor(() => expect(patch).toHaveBeenCalledTimes(1));
    expect(disable).not.toHaveBeenCalled();
  });

  it('iphone_tab_needs_install_first', () => {
    vi.spyOn(push, 'status').mockReturnValue('needs-install');
    open();
    expect(text()).toContain('Install the app first (Share → Add to Home Screen)');
    expect(document.querySelector<HTMLInputElement>('#notify-done')!.disabled).toBe(true);
    expect(document.querySelector<HTMLInputElement>('#notify-due')!.disabled).toBe(true);
  });
});
