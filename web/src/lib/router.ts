export type Route =
  | { name: 'home' }
  | { name: 'login' }
  | { name: 'households' }
  | { name: 'household'; id: number }
  | { name: 'new-chore'; householdId: number }
  | { name: 'chore'; id: number }
  | { name: 'invite'; token: string } // leak-check: ok
  | { name: 'settings' }
  | { name: 'account' };

const ID = /^[1-9]\d{0,15}$/;
const INVITE_RE = /^[A-Za-z0-9_-]{1,128}$/;

export function matchRoute(path: string): Route | null {
  const parts = path.split('/').filter((p, i, a) => p !== '' || (i > 0 && i < a.length - 1));
  const [a, b, c, ...rest] = parts;
  if (rest.length) return null;
  if (c !== undefined) return a === 'households' && b !== undefined && ID.test(b) && c === 'new-chore' ? { name: 'new-chore', householdId: Number(b) } : null;
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

type Listener = (r: Route, url: URL) => void;
const listeners = new Set<Listener>();

function current(): { route: Route; url: URL } {
  const url = new URL(location.href);
  let route = matchRoute(url.pathname);
  if (!route) {
    history.replaceState(null, '', '/');
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

export function navigate(path: string, opts: { replace?: boolean } = {}): void {
  if (opts.replace) history.replaceState(null, '', path);
  else history.pushState(null, '', path);
  emit();
}

export function onRoute(l: Listener): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function startRouter(): void {
  addEventListener('popstate', emit);
  document.addEventListener('click', (e) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
    const a = (e.target as Element | null)?.closest?.('a');
    if (!a || a.target || a.hasAttribute('download') || a.dataset.external !== undefined) return;
    const url = new URL(a.href, location.href);
    if (url.origin !== location.origin || url.pathname.startsWith('/auth/') || url.pathname.startsWith('/api/')) return;
    e.preventDefault();
    navigate(url.pathname + url.search + url.hash);
  });
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
