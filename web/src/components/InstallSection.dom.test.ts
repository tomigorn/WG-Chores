// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, describe, expect, it } from 'vitest';
import InstallSection from './InstallSection.svelte';
import { button, dialogs, text } from '../screens/testkit';
import { captureInstallPrompt, dismissBanner } from '../lib/install';

const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1';
const IPHONE_CHROME = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0 Mobile/15E148 Safari/604.1';
const ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';
const DESKTOP = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';

let app: ReturnType<typeof mount> | null = null;

function open(ua: string, standalone = false) {
  dialogs();
  app = mount(InstallSection, { target: document.body, props: { ua, standalone } });
  flushSync();
}

function offerInstall(onprompt: () => void) {
  captureInstallPrompt();
  dispatchEvent(
    Object.assign(new Event('beforeinstallprompt', { cancelable: true }), {
      prompt: async () => onprompt(),
      userChoice: Promise.resolve({ outcome: 'accepted' as const }),
    }),
  );
  flushSync();
}

afterEach(() => {
  if (app) unmount(app);
  app = null;
  localStorage.clear();
  document.body.innerHTML = '';
});

describe('InstallSection', () => {
  it('section_titled_app', () => {
    open(ANDROID);
    expect(document.querySelector('h2')!.textContent).toBe('App');
  });

  it('android_offer_opens_the_real_install_dialog', async () => {
    open(ANDROID);
    let prompted = 0;
    offerInstall(() => void prompted++);
    button('Install')!.click();
    await new Promise((r) => setTimeout(r));
    expect(prompted).toBe(1);
  });

  it('android_without_offer_shows_menu_and_shortcut_fix', () => {
    open(ANDROID);
    expect(button('Install')).toBeUndefined();
    expect(text()).toContain('⋮ → Install and create shortcut');
    expect(text()).toContain('Home screen shortcuts');
  });

  it('desktop_without_offer_points_to_browser_menu', () => {
    open(DESKTOP);
    expect(button('Install')).toBeUndefined();
    expect(text()).toContain("Install it from your browser's menu");
  });

  it('ios_safari_opens_the_guide', () => {
    open(IPHONE);
    button('Show how')!.click();
    flushSync();
    expect(document.querySelectorAll('dialog ol li').length).toBe(3);
  });

  it('ios_other_browser_says_open_in_safari', () => {
    open(IPHONE_CHROME);
    expect(text()).toContain('open this page in Safari first');
    expect(button('Show how')).toBeUndefined();
  });

  it('installed_app_shows_installed', () => {
    open(ANDROID, true);
    expect(text()).toContain('Installed');
    expect(button('Install')).toBeUndefined();
  });

  it('shown_even_after_the_banner_was_dismissed', () => {
    dismissBanner();
    open(IPHONE);
    expect(button('Show how')).toBeDefined();
  });
});
