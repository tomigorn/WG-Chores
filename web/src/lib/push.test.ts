import { describe, expect, it, vi } from 'vitest';
import { createPush, pushStatus, urlBase64ToBytes, type PushDeps } from './push';

const KEY = 'BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U';

function fakeSub(endpoint = 'https://fcm.googleapis.com/fcm/send/abc', key = urlBase64ToBytes(KEY)) {
  return {
    endpoint,
    options: { applicationServerKey: key.buffer as ArrayBuffer },
    toJSON: () => ({ endpoint, expirationTime: null, keys: { p256dh: 'p', auth: 'a' } }),
    unsubscribe: vi.fn(async () => true),
  };
}

function deps(permission: NotificationPermission, existing: ReturnType<typeof fakeSub> | null = null) {
  let current = existing;
  const order: string[] = [];
  const pushManager = {
    getSubscription: vi.fn(async () => current),
    subscribe: vi.fn(async () => {
      order.push('subscribe');
      current = fakeSub();
      return current;
    }),
  };
  const d = {
    permission: vi.fn(() => permission),
    requestPermission: vi.fn(async () => {
      order.push('permission');
      return 'granted' as NotificationPermission;
    }),
    registration: vi.fn(async () => ({ pushManager })),
    api: {
      pushKey: vi.fn(async () => ({ key: KEY })),
      subscribe: vi.fn(async () => void order.push('post')),
      unsubscribe: vi.fn(async () => {}),
    },
  } satisfies PushDeps;
  return { d, pushManager, order };
}

describe('push', () => {
  it('push_subscribes_only_after_toggle', async () => {
    const { d, pushManager, order } = deps('default');
    const p = createPush(d);
    await p.refresh(true, 'en');
    expect(d.requestPermission).not.toHaveBeenCalled();
    expect(pushManager.subscribe).not.toHaveBeenCalled();
    expect(d.api.subscribe).not.toHaveBeenCalled();

    expect(await p.enable('de')).toBe('subscribed');
    expect(order).toEqual(['permission', 'subscribe', 'post']);
    expect(pushManager.subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: urlBase64ToBytes(KEY) });
    expect(d.api.subscribe).toHaveBeenCalledWith({ endpoint: 'https://fcm.googleapis.com/fcm/send/abc', expirationTime: null, keys: { p256dh: 'p', auth: 'a' } }, 'de');
  });

  it('permission_asked_before_any_await', () => {
    const { d } = deps('default');
    void createPush(d).enable('en');
    expect(d.requestPermission).toHaveBeenCalledTimes(1);
  });

  it('denied_permission_reports_blocked', async () => {
    const { d, pushManager } = deps('default');
    d.requestPermission.mockResolvedValue('denied');
    expect(await createPush(d).enable('en')).toBe('blocked');
    const closed = deps('default');
    closed.d.requestPermission.mockResolvedValue('default');
    expect(await createPush(closed.d).enable('en')).toBe('dismissed');
    expect(pushManager.subscribe).not.toHaveBeenCalled();
  });

  it('disable_unsubscribes_and_deletes', async () => {
    const sub = fakeSub();
    const { d } = deps('granted', sub);
    await createPush(d).disable();
    expect(sub.unsubscribe).toHaveBeenCalled();
    expect(d.api.unsubscribe).toHaveBeenCalledWith(sub.endpoint);
  });

  it('refresh_reposts_existing_subscription', async () => {
    const sub = fakeSub();
    const { d, pushManager } = deps('granted', sub);
    await createPush(d).refresh(true, 'en');
    expect(pushManager.subscribe).not.toHaveBeenCalled();
    expect(d.api.subscribe).toHaveBeenCalledWith(sub.toJSON(), 'en');
  });

  it('refresh_replaces_subscription_with_other_key', async () => {
    const old = fakeSub('https://fcm.googleapis.com/fcm/send/old', new Uint8Array(65).fill(4));
    const { d, pushManager } = deps('granted', old);
    await createPush(d).refresh(true, 'en');
    expect(old.unsubscribe).toHaveBeenCalled();
    expect(pushManager.subscribe).toHaveBeenCalled();
    expect(d.api.subscribe).toHaveBeenCalledWith(expect.objectContaining({ endpoint: 'https://fcm.googleapis.com/fcm/send/abc' }), 'en');
  });

  it('refresh_drops_subscription_when_both_off', async () => {
    const sub = fakeSub();
    const { d } = deps('granted', sub);
    await createPush(d).refresh(false, 'en');
    expect(sub.unsubscribe).toHaveBeenCalled();
    expect(d.api.unsubscribe).toHaveBeenCalledWith(sub.endpoint);
    expect(d.api.subscribe).not.toHaveBeenCalled();
  });

  it('refresh_never_throws', async () => {
    const { d } = deps('granted', fakeSub());
    d.api.subscribe.mockRejectedValue(new Error('offline'));
    await expect(createPush(d).refresh(true, 'en')).resolves.toBeUndefined();
  });

  it('push_status_matrix', () => {
    const iphone = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1';
    const android = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36';
    expect(pushStatus({ ua: iphone, standalone: false, supported: false, permission: 'default' })).toBe('needs-install');
    expect(pushStatus({ ua: iphone, standalone: true, supported: true, permission: 'default' })).toBe('ready');
    expect(pushStatus({ ua: android, standalone: false, supported: true, permission: 'denied' })).toBe('blocked');
    expect(pushStatus({ ua: android, standalone: false, supported: true, permission: 'granted' })).toBe('ready');
    expect(pushStatus({ ua: android, standalone: false, supported: false, permission: 'default' })).toBe('unsupported');
    const ipad = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15';
    expect(pushStatus({ ua: ipad, standalone: false, supported: false, permission: 'default', touchPoints: 5 })).toBe('needs-install');
    expect(pushStatus({ ua: ipad, standalone: false, supported: true, permission: 'default', touchPoints: 0 })).toBe('ready');
  });

  it('sign_out_drops_this_device_and_returns_its_endpoint_even_offline', async () => {
    const sub = fakeSub();
    const { d } = deps('granted', sub);
    d.api.unsubscribe.mockRejectedValue(new Error('offline'));
    expect(await createPush(d).signOut()).toBe('https://fcm.googleapis.com/fcm/send/abc');
    expect(sub.unsubscribe).toHaveBeenCalled();
    expect(d.api.unsubscribe).toHaveBeenCalledWith('https://fcm.googleapis.com/fcm/send/abc');

    const deleted = deps('granted', fakeSub());
    expect(await createPush(deleted.d).signOut(false)).toBe('https://fcm.googleapis.com/fcm/send/abc');
    expect(deleted.d.api.unsubscribe).not.toHaveBeenCalled();

    const none = deps('granted');
    expect(await createPush(none.d).signOut()).toBeNull();
    const broken = deps('granted');
    broken.d.registration.mockRejectedValue(new Error('no worker'));
    expect(await createPush(broken.d).signOut()).toBeNull();
  });
});
