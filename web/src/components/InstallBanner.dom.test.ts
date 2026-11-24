// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, describe, expect, it } from 'vitest';
import InstallBanner from './InstallBanner.svelte';
import { button, dialogs, text } from '../screens/testkit';
import { captureInstallPrompt, dismissedAt } from '../lib/install';
import { clearSnack, onSnack } from '../lib/snack';
import type { Snack } from './DoneSnackbar.svelte';

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1';
const IPHONE_CHROME = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0 Mobile/15E148 Safari/604.1';
const ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';

let app: ReturnType<typeof mount> | null = null;

function open(ua: string, standalone = false) {
  dialogs();
  app = mount(InstallBanner, { target: document.body, props: { ua, standalone } });
  flushSync();
}

afterEach(() => {
  if (app) unmount(app);
  app = null;
  localStorage.clear();
  document.body.innerHTML = '';
});

describe('InstallBanner', () => {
  it('ios_safari_shows_picture_guide', () => {
    open(IPHONE);
    expect(text()).toContain('Add WG Chores to your home screen – for notifications');
    expect(document.querySelector('.banner svg')).not.toBeNull();
    button('Show how')!.click();
    flushSync();
    const steps = [...document.querySelectorAll('dialog ol li')].map((l) => l.textContent!.replace(/\s+/g, ' ').trim());
    expect(steps).toEqual(['Tap Share in the Safari toolbar', 'Choose “Add to Home Screen”', 'Tap “Add”']);
    expect(document.querySelectorAll('dialog ol li svg').length).toBe(3);
  });

  it('ios_other_browser_says_open_in_safari', () => {
    open(IPHONE_CHROME);
    expect(text()).toContain('open this page in Safari first');
    expect(button('Show how')).toBeUndefined();
  });

  it('android_uses_the_real_install_prompt', async () => {
    captureInstallPrompt();
    open(ANDROID);
    expect(document.querySelector('.banner')).toBeNull();
    let prompted = 0;
    const e = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
      prompt: async () => void prompted++,
      userChoice: Promise.resolve({ outcome: 'accepted' as const }),
    });
    dispatchEvent(e);
    flushSync();
    expect(e.defaultPrevented).toBe(true);
    expect(text()).toContain('Install WG Chores – for notifications');
    button('Install')!.click();
    await new Promise((r) => setTimeout(r));
    flushSync();
    expect(prompted).toBe(1);
    expect(document.querySelector('.banner')).toBeNull();
  });

  it('dismiss_hides_and_remembers', () => {
    open(IPHONE);
    button('Dismiss')!.click();
    flushSync();
    expect(document.querySelector('.banner')).toBeNull();
    expect(dismissedAt()).not.toBeNull();
  });

  it('dismiss_notes_install_later_in_settings', () => {
    let shown: Snack | null = null;
    const off = onSnack((s) => (shown = s));
    open(IPHONE);
    button('Dismiss')!.click();
    flushSync();
    off();
    expect(shown).toMatchObject({ text: 'You can install later in Settings.', duration: 3000 });
    clearSnack();
  });

  it('never_in_the_installed_app', () => {
    open(IPHONE, true);
    expect(document.querySelector('.banner')).toBeNull();
  });
});
