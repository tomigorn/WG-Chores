export type Route =
  | { name: 'home' }
  | { name: 'login' }
  | { name: 'households' }
  | { name: 'household'; id: number }
  | { name: 'new-chore'; householdId: number }
  | { name: 'household-settings'; id: number }
  | { name: 'chore'; id: number }
  | { name: 'chore-edit'; id: number }
  | { name: 'invite'; token: string } // leak-check: ok
  | { name: 'settings' }
  | { name: 'account' };

const ID = /^[1-9]\d{0,15}$/;
const INVITE_RE = /^[A-Za-z0-9_-]{1,128}$/;

export function matchRoute(path: string): Route | null {
  const parts = path.split('/').filter((p, i, a) => p !== '' || (i > 0 && i < a.length - 1));
  const [a, b, c, ...rest] = parts;
  if (rest.length) return null;
  if (c !== undefined) {
    if (b === undefined || !ID.test(b)) return null;
    if (a === 'households' && c === 'new-chore') return { name: 'new-chore', householdId: Number(b) };
    if (a === 'households' && c === 'settings') return { name: 'household-settings', id: Number(b) };
    if (a === 'chores' && c === 'edit') return { name: 'chore-edit', id: Number(b) };
    return null;
  }
  if (a === undefined) return { name: 'home' };
  if (b === undefined) {
    if (a === 'login' || a === 'households' || a === 'settings' || a === 'account') return { name: a };
    return null;
  }
  if (a === 'households' && ID.test(b)) return { name: 'household', id: Number(b) };
  if (a === 'chores' && ID.test(b)) return { name: 'chore', id: Number(b) };
  if (a === 'invite' && INVITE_RE.test(b)) return { name: 'invite', token: b };
  return null;
}

export function safeNext(p: string | null | undefined): string {
  if (!p || !p.startsWith('/') || p.startsWith('//') || p.startsWith('/\\')) return '/';
  const r = matchRoute(p.split(/[?#]/)[0]!);
  return r && r.name !== 'login' ? p : '/';
}

interface Entry {
  key: string;
  from?: string;
}

const SCROLL_KEY = 'wgc.scroll';
const MAX_POSITIONS = 50;
let currentKey: string | null = null;
let pending: number | null = null;
let memory: Record<string, number> | null = null;

function newKey(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}

function entry(): Entry | null {
  const s = history.state as Partial<Entry> | null;
  return s && typeof s.key === 'string' ? (s as Entry) : null;
}

function here(): string {
  return location.pathname + location.search;
}

function positions(): Record<string, number> {
  if (memory) return memory;
  try {
    const raw: unknown = JSON.parse(sessionStorage.getItem(SCROLL_KEY) ?? '{}');
    memory = raw && typeof raw === 'object' ? (raw as Record<string, number>) : {};
  } catch {
    memory = {};
  }
  return memory;
}

function savePosition(): void {
  if (!currentKey) return;
  const p = positions();
  delete p[currentKey];
  p[currentKey] = Math.round(scrollY);
  const keys = Object.keys(p);
  for (const k of keys.slice(0, Math.max(0, keys.length - MAX_POSITIONS))) delete p[k];
  try {
    sessionStorage.setItem(SCROLL_KEY, JSON.stringify(p));
  } catch {
    /* storage unavailable */
  }
}

type Listener = (r: Route, url: URL) => void;
const listeners = new Set<Listener>();

function current(): { route: Route; url: URL } {
  const url = new URL(location.href);
  let route = matchRoute(url.pathname);
  if (!route) {
    currentKey = newKey();
    history.replaceState({ key: currentKey } satisfies Entry, '', '/');
    route = { name: 'home' };
  }
  return { route, url: new URL(location.href) };
}

function emit(): void {
  const { route, url } = current();
  for (const l of listeners) l(route, url);
}

export function currentRoute(): { route: Route; url: URL } {
  return current();
}

/** Leaves the app, e.g. for the SSO provider. */
export function leave(url: string): void {
  location.href = url;
}

export function navigate(path: string, opts: { replace?: boolean } = {}): void {
  savePosition();
  const from = opts.replace ? entry()?.from : here();
  const state: Entry = { key: newKey(), ...(from ? { from } : {}) };
  if (opts.replace) history.replaceState(state, '', path);
  else history.pushState(state, '', path);
  currentKey = state.key;
  pending = null;
  emit();
  scrollTo(0, 0);
}

/** In-app back arrow: steps back when the previous entry is the target, so its scroll position comes back. */
export function backTo(path: string): boolean {
  if (entry()?.from !== path) return false;
  history.back();
  return true;
}

/** Called by a screen once its content is rendered; scrolls to where the entry was left on back/forward. */
export function restoreScroll(): void {
  const y = pending;
  pending = null;
  if (y !== null) scrollTo(0, y);
}

export function onRoute(l: Listener): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

function popped(): void {
  savePosition();
  let e = entry();
  if (!e) {
    e = { key: newKey() };
    history.replaceState(e, '');
  }
  currentKey = e.key;
  pending = positions()[e.key] ?? null;
  emit();
}

export function startRouter(): () => void {
  if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
  let e = entry();
  if (!e) {
    e = { key: newKey() };
    history.replaceState(e, '');
  }
  currentKey = e.key;
  addEventListener('popstate', popped);
  document.addEventListener('click', click);
  return () => {
    removeEventListener('popstate', popped);
    document.removeEventListener('click', click);
  };
}

function click(e: MouseEvent): void {
  if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  const a = (e.target as Element | null)?.closest?.('a');
  if (!a || a.target || a.hasAttribute('download') || a.dataset.external !== undefined) return;
  const url = new URL(a.href, location.href);
  if (url.origin !== location.origin || url.pathname.startsWith('/auth/') || url.pathname.startsWith('/api/')) return;
  e.preventDefault();
  navigate(url.pathname + url.search + url.hash);
}

const NEXT_KEY = 'wgc.next';

export function rememberNext(path: string): void {
  try {
    sessionStorage.setItem(NEXT_KEY, safeNext(path));
  } catch {
    /* storage unavailable */
  }
}

export function takeNext(): string {
  try {
    const p = sessionStorage.getItem(NEXT_KEY);
    sessionStorage.removeItem(NEXT_KEY);
    return safeNext(p);
  } catch {
    return '/';
  }
}

export function inviteParam(url: URL): string | null {
  const t = url.searchParams.get('invite');
  return t && INVITE_RE.test(t) ? t : null;
}
