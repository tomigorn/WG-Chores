interface WorkerLike {
  state: string;
  postMessage(m: unknown): void;
  addEventListener(t: 'statechange', cb: () => void): void;
}

interface RegistrationLike {
  waiting: WorkerLike | null;
  installing: WorkerLike | null;
  update(): Promise<unknown>;
  addEventListener(t: 'updatefound', cb: () => void): void;
}

export interface ContainerLike {
  controller: unknown;
  register(url: string, o?: { scope?: string }): Promise<RegistrationLike>;
  addEventListener(t: 'controllerchange' | 'message', cb: (e: { data?: unknown }) => void): void;
}

export interface UpdateHooks {
  onUpdate(apply: () => void): void;
  reload(): void;
  navigate(path: string): void;
}

/** Registers /sw.js; a new version waits until the user chooses to reload, nothing reloads on its own. */
export async function watchUpdates(c: ContainerLike, h: UpdateHooks): Promise<RegistrationLike> {
  let asked = false;
  c.addEventListener('controllerchange', () => {
    if (asked) h.reload();
  });
  c.addEventListener('message', (e) => {
    const d = e.data as { type?: string; url?: unknown } | null | undefined;
    const url = d?.type === 'navigate' ? d.url : null;
    if (typeof url === 'string' && /^\/(?![/\\])/.test(url)) h.navigate(url);
  });
  const reg = await c.register('/sw.js', { scope: '/' });
  const offer = (w: WorkerLike) =>
    h.onUpdate(() => {
      asked = true;
      w.postMessage({ type: 'skip-waiting' });
    });
  if (reg.waiting && c.controller) offer(reg.waiting);
  reg.addEventListener('updatefound', () => {
    const w = reg.installing;
    w?.addEventListener('statechange', () => {
      if (w.state === 'installed' && c.controller) offer(w);
    });
  });
  return reg;
}
