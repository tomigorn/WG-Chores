export type Route = 'navigate' | 'precached' | 'network';

export interface Build {
  version: string;
  files: string[];
}

const PREFIX = 'wgc-shell-';
export const NAV_TIMEOUT_MS = 3000;

export const cacheName = (version: string) => PREFIX + version;

export function staleCaches(keys: string[], version: string): string[] {
  return keys.filter((k) => k.startsWith(PREFIX) && k !== cacheName(version));
}

export function swRoute(r: { url: string; mode: RequestMode; method: string }, origin: string, precache: string[]): Route {
  if (r.method !== 'GET') return 'network';
  const u = new URL(r.url);
  if (u.origin !== origin) return 'network';
  const p = u.pathname;
  if (p === '/api' || p.startsWith('/api/') || p === '/auth' || p.startsWith('/auth/') || p.startsWith('/healthz') || p === '/sw.js') return 'network';
  if (r.mode === 'navigate') return 'navigate';
  return precache.includes(p) ? 'precached' : 'network';
}

export function safeUrl(u: unknown): string {
  return typeof u === 'string' && /^\/(?![/\\])/.test(u) ? u : '/';
}

/** Title and body of a due notification, as in server/src/notify/texts.rs. */
export function dueTexts(lang: string | undefined, household: string, names: string[]): [string, string] {
  const de = lang === 'de';
  if (names.length === 1) return [de ? `${names[0]} ist fällig` : `${names[0]} is due`, household];
  return [de ? `${names.length} Aufgaben sind fällig` : `${names.length} chores are due`, names.join(', ')];
}

const str = (v: unknown) => (typeof v === 'string' ? v : undefined);
const strings = (v: unknown) => (Array.isArray(v) && v.every((x) => typeof x === 'string') ? (v as string[]) : undefined);

/** `shown`: notifications with the same tag still in the shade; a due push extends them instead of replacing them. */
export function notificationFromPush(data: unknown, shown: readonly { data?: unknown }[] = []) {
  const d = (data && typeof data === 'object' ? data : {}) as Record<string, unknown>;
  const tag = str(d.tag);
  const url = safeUrl(d.url);
  const n = {
    title: str(d.title) || 'WG Chores',
    options: { body: str(d.body) ?? '', tag, data: { url } as { url: string; names?: string[]; household?: string; lang?: string }, icon: '/icons/icon-192.png', badge: '/icons/badge-96.png' },
  };
  const names = strings(d.names);
  const household = str(d.household);
  if (!names || household === undefined || !tag?.startsWith('due-')) return n;
  const lang = str(d.lang);
  const before = shown.map((s) => strings((s.data as { names?: unknown } | null | undefined)?.names)).find((x) => x);
  const all = before ? [...new Set([...before, ...names])] : names;
  n.options.data = { url, names: all, household, lang };
  if (before) [n.title, n.options.body] = dueTexts(lang, household, all);
  return n;
}

export function targetClient<C extends { url: string; focused: boolean }>(list: readonly C[], origin: string): C | null {
  const own = list.filter((c) => new URL(c.url).origin === origin);
  return own.find((c) => c.focused) ?? own[0] ?? null;
}

interface WindowClientLike {
  url: string;
  focused: boolean;
  focus(): Promise<unknown>;
  postMessage(m: unknown): void;
}

interface CacheLike {
  addAll(r: RequestInfo[]): Promise<void>;
  match(r: RequestInfo): Promise<Response | undefined>;
}

export interface Scope {
  location: { origin: string };
  addEventListener(type: string, cb: (e: never) => void): void;
  skipWaiting(): Promise<void>;
  fetch(r: Request | string): Promise<Response>;
  caches: { open(n: string): Promise<CacheLike>; keys(): Promise<string[]>; delete(n: string): Promise<boolean> };
  clients: { claim(): Promise<void>; matchAll(o: { type: 'window'; includeUncontrolled: boolean }): Promise<readonly WindowClientLike[]>; openWindow(url: string): Promise<unknown> };
  registration: { showNotification(title: string, o: NotificationOptions): Promise<void>; getNotifications(o: { tag?: string }): Promise<readonly { data?: unknown }[]> };
}

interface Ext {
  waitUntil(p: Promise<unknown>): void;
}

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('timeout')), ms);
    p.then(
      (v) => (clearTimeout(t), resolve(v)),
      (e) => (clearTimeout(t), reject(e)),
    );
  });
}

export function registerHandlers(self: Scope, build: Build): void {
  const name = cacheName(build.version);
  const origin = self.location.origin;

  async function shell(): Promise<Response> {
    const c = await self.caches.open(name);
    return (await c.match('/index.html')) ?? Response.error();
  }

  async function navigate(req: Request): Promise<Response> {
    try {
      const res = await withTimeout(self.fetch(req), NAV_TIMEOUT_MS);
      return res.status >= 500 ? await shell() : res;
    } catch {
      return shell();
    }
  }

  async function precached(req: Request): Promise<Response> {
    const hit = await (await self.caches.open(name)).match(req);
    return hit ?? self.fetch(req);
  }

  self.addEventListener('install', (e: Ext) => {
    e.waitUntil(self.caches.open(name).then((c) => c.addAll(build.files.map((f) => new Request(new URL(f, origin), { cache: 'reload' })))));
  });

  self.addEventListener('activate', (e: Ext) => {
    e.waitUntil(
      self.caches
        .keys()
        .then((keys) => Promise.all(staleCaches(keys, build.version).map((k) => self.caches.delete(k))))
        .then(() => self.clients.claim()),
    );
  });

  self.addEventListener('message', (e: { data: unknown }) => {
    if ((e.data as { type?: string } | null)?.type === 'skip-waiting') void self.skipWaiting();
  });

  self.addEventListener('fetch', (e: { request: Request; respondWith(r: Promise<Response>): void }) => {
    const route = swRoute(e.request, origin, build.files);
    if (route === 'navigate') e.respondWith(navigate(e.request));
    else if (route === 'precached') e.respondWith(precached(e.request));
  });

  self.addEventListener('push', (e: Ext & { data: { json(): unknown } | null }) => {
    let data: unknown = null;
    try {
      data = e.data?.json();
    } catch {
      /* not JSON */
    }
    const tag = (data as { tag?: unknown } | null)?.tag;
    const shown = typeof tag === 'string' ? self.registration.getNotifications({ tag }).catch(() => []) : Promise.resolve([]);
    e.waitUntil(
      shown.then((s) => {
        const n = notificationFromPush(data, s);
        return self.registration.showNotification(n.title, n.options);
      }),
    );
  });

  self.addEventListener('notificationclick', (e: Ext & { notification: { close(): void; data?: { url?: unknown } } }) => {
    e.notification.close();
    const url = safeUrl(e.notification.data?.url);
    e.waitUntil(
      self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(async (list) => {
        const c = targetClient(list, origin);
        if (!c) {
          await self.clients.openWindow(url);
          return;
        }
        await c.focus();
        c.postMessage({ type: 'navigate', url });
      }),
    );
  });
}
