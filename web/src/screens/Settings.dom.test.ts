// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import Settings from './Settings.svelte';
import { api, OfflineError } from '../lib/api';
import * as i18n from '../lib/i18n';
import { clearSnack } from '../lib/snack';
import { me, text } from './testkit';

let app: ReturnType<typeof mount> | null = null;

function open() {
  const props = { me, lang: 'en' as const, theme: 'system' as const, ontheme: vi.fn(), onme: vi.fn() };
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
});
