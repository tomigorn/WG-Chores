import { api, type PushSubscriptionInput } from './api';
import type { Lang } from './i18n';
import { isIos, isStandalone, touchPoints } from './install';

export type PushStatus = 'ready' | 'blocked' | 'needs-install' | 'unsupported';
export type EnableResult = 'subscribed' | 'blocked' | 'dismissed' | 'unsupported';

interface SubscriptionLike {
  endpoint: string;
  options?: { applicationServerKey?: ArrayBuffer | null };
  toJSON(): unknown;
  unsubscribe(): Promise<boolean>;
}

interface PushManagerLike {
  getSubscription(): Promise<SubscriptionLike | null>;
  subscribe(o: { userVisibleOnly: boolean; applicationServerKey: Uint8Array<ArrayBuffer> }): Promise<SubscriptionLike>;
}

export interface PushDeps {
  permission(): NotificationPermission;
  requestPermission(): Promise<NotificationPermission>;
  registration(): Promise<{ pushManager: PushManagerLike } | null>;
  api: {
    pushKey(): Promise<{ key: string }>;
    subscribe(sub: PushSubscriptionInput, lang: Lang): Promise<void>;
    unsubscribe(endpoint: string): Promise<void>;
  };
}

export function pushStatus(env: { ua: string; standalone: boolean; supported: boolean; permission: NotificationPermission; touchPoints?: number }): PushStatus {
  if (isIos(env.ua, env.touchPoints) && !env.standalone) return 'needs-install';
  if (!env.supported) return 'unsupported';
  return env.permission === 'denied' ? 'blocked' : 'ready';
}

export function urlBase64ToBytes(s: string): Uint8Array<ArrayBuffer> {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4));
  const out = new Uint8Array(b.length);
  for (let i = 0; i < b.length; i++) out[i] = b.charCodeAt(i);
  return out;
}

function sameKey(sub: SubscriptionLike, key: Uint8Array): boolean {
  const k = sub.options?.applicationServerKey;
  if (!k) return true;
  const a = new Uint8Array(k);
  return a.length === key.length && a.every((v, i) => v === key[i]);
}

export function createPush(d: PushDeps) {
  async function subscribeAndPost(lang: Lang): Promise<boolean> {
    const reg = await d.registration();
    if (!reg) return false;
    const key = urlBase64ToBytes((await d.api.pushKey()).key);
    let sub = await reg.pushManager.getSubscription();
    if (sub && !sameKey(sub, key)) {
      await sub.unsubscribe();
      sub = null;
    }
    sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
    await d.api.subscribe(sub.toJSON() as PushSubscriptionInput, lang);
    return true;
  }

  async function drop(): Promise<void> {
    const sub = await (await d.registration())?.pushManager.getSubscription();
    if (!sub) return;
    await sub.unsubscribe();
    await d.api.unsubscribe(sub.endpoint);
  }

  return {
    /** Call straight from the click: iOS only shows the permission prompt for a user gesture. */
    async enable(lang: Lang): Promise<EnableResult> {
      const perm = d.permission() === 'granted' ? 'granted' : await d.requestPermission();
      if (perm === 'denied') return 'blocked';
      if (perm !== 'granted') return 'dismissed';
      return (await subscribeAndPost(lang)) ? 'subscribed' : 'unsupported';
    },
    disable: drop,
    /** Sign-out: drops this device's subscription (server side best effort, the logout call covers it too); never throws. */
    async signOut(server = true): Promise<string | null> {
      try {
        const sub = await (await d.registration())?.pushManager.getSubscription();
        if (!sub) return null;
        const endpoint = sub.endpoint;
        await sub.unsubscribe().catch(() => {});
        if (server) await d.api.unsubscribe(endpoint).catch(() => {});
        return endpoint;
      } catch {
        return null;
      }
    },
    /** On app start: keeps this device's subscription in step with the account, never prompts. */
    async refresh(wanted: boolean, lang: Lang): Promise<void> {
      try {
        if (d.permission() !== 'granted') return;
        if (wanted) await subscribeAndPost(lang);
        else await drop();
      } catch {
        /* retried on the next start */
      }
    },
  };
}

function supported(): boolean {
  return typeof navigator !== 'undefined' && 'serviceWorker' in navigator && typeof PushManager !== 'undefined' && typeof Notification !== 'undefined';
}

export function currentPushStatus(): PushStatus {
  return pushStatus({
    ua: typeof navigator === 'undefined' ? '' : navigator.userAgent,
    standalone: isStandalone(),
    supported: supported(),
    permission: supported() ? Notification.permission : 'default',
    touchPoints: touchPoints(),
  });
}

export const push = {
  status: currentPushStatus,
  ...createPush({
    permission: () => (supported() ? Notification.permission : 'denied'),
    requestPermission: () => Notification.requestPermission(),
    registration: async () => (supported() && (await navigator.serviceWorker.getRegistration()) ? await navigator.serviceWorker.ready : null),
    api: { pushKey: () => api.pushKey(), subscribe: (s, l) => api.subscribe(s, l), unsubscribe: (e) => api.unsubscribe(e) },
  }),
};
