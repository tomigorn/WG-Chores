import type { BrowserContext, Page } from '@playwright/test';
import { Api } from './support/api';
import { expect, test, useServer } from './support/fixtures';

const box = useServer();

type Perm = 'granted' | 'denied' | 'default';

/** Headless Chromium has no push service: PushManager and the permission prompt are replaced by fakes. */
async function fakePush(context: BrowserContext, prompt: Perm, current: Perm = 'default') {
  await context.addInitScript(
    ([prompt, current]) => {
      let perm = current;
      Object.defineProperty(Notification, 'permission', { get: () => perm, configurable: true });
      Notification.requestPermission = async () => (perm = prompt);
      const b64 = (b: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(b))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
      let sub: unknown = null;
      PushManager.prototype.getSubscription = async () => sub as PushSubscription | null;
      PushManager.prototype.subscribe = async function (o?: PushSubscriptionOptionsInit) {
        const pair = (await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits'])) as CryptoKeyPair;
        const p256dh = b64(await crypto.subtle.exportKey('raw', pair.publicKey));
        const auth = b64(crypto.getRandomValues(new Uint8Array(16)).buffer);
        const endpoint = `https://fcm.googleapis.com/fcm/send/e2e-${Math.random().toString(36).slice(2)}`;
        const key = o?.applicationServerKey as Uint8Array | undefined;
        sub = {
          endpoint,
          options: { applicationServerKey: key ? key.slice().buffer : null },
          toJSON: () => ({ endpoint, expirationTime: null, keys: { p256dh, auth } }),
          unsubscribe: async () => ((sub = null), true),
        };
        return sub as PushSubscription;
      };
    },
    [prompt, current] as const,
  );
}

async function openSettings(page: Page, context: BrowserContext) {
  const anna = await Api.register(box.server.url, 'Anna');
  // new accounts start with both kinds on; start from off so turning one on is the first ask
  await anna.ok('PATCH', '/api/v1/me', { notify_done: false, notify_due: false });
  await anna.signInContext(context);
  await page.goto(box.server.url + '/settings');
  await page.waitForFunction(() => navigator.serviceWorker.getRegistration().then((r) => !!r));
  return anna;
}

const me = (a: Api) => a.ok<{ notify_done: boolean; notify_due: boolean }>('GET', '/api/v1/me');

test.describe('browsers with push', () => {
  test.skip(({ isMobile, userAgent }) => !!isMobile && /iPhone/.test(userAgent ?? ''), 'iPhone tabs cannot subscribe');

  test('turning a toggle on asks once, subscribes and saves it; both off unsubscribes', async ({ page, context }) => {
    await fakePush(context, 'granted');
    const anna = await openSettings(page, context);
    const posted = page.waitForResponse((r) => r.url().endsWith('/api/v1/push/subscriptions') && r.request().method() === 'POST');
    await page.getByRole('switch', { name: /Done by others/ }).check();
    expect((await posted).status()).toBe(204);
    await expect.poll(async () => (await me(anna)).notify_done).toBe(true);
    await expect(page.getByText('Notifications arrive once the app is installed.')).toBeVisible();

    const deleted = page.waitForResponse((r) => r.url().endsWith('/api/v1/push/subscriptions') && r.request().method() === 'DELETE');
    await page.getByRole('switch', { name: /Done by others/ }).uncheck();
    expect((await deleted).status()).toBe(204);
    expect((await me(anna)).notify_done).toBe(false);
  });

  test('a closed prompt leaves the switch off with a hint', async ({ page, context }) => {
    await fakePush(context, 'default');
    const anna = await openSettings(page, context);
    await page.getByRole('switch', { name: /Chores due/ }).click();
    await expect(page.getByText('Notifications were not allowed.')).toBeVisible();
    await expect(page.getByRole('switch', { name: /Chores due/ })).not.toBeChecked();
    expect((await me(anna)).notify_due).toBe(false);
  });

  test('blocked in the browser: the note says where to allow it', async ({ page, context }) => {
    await fakePush(context, 'denied', 'denied');
    await openSettings(page, context);
    await expect(page.getByText(/Blocked in browser settings on this device/)).toBeVisible();
  });
});

test('iPhone tab: switches disabled until the app is installed', async ({ page, context, userAgent }) => {
  test.skip(!/iPhone/.test(userAgent ?? ''), 'only the iPhone project');
  await openSettings(page, context);
  await expect(page.getByText('Install the app first (Share → Add to Home Screen)')).toBeVisible();
  await expect(page.getByRole('switch', { name: /Done by others/ })).toBeDisabled();
});
