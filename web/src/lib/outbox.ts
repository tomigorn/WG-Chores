import { ApiError, OfflineError } from './api';

export interface OutboxItem {
  uuid: string;
  choreId: number;
  doneOn: string;
  note: string | null;
  /** Absent: create the completion; 'delete': an Undo still to be sent. */
  kind?: 'delete';
  /** A send was started, so the server may already have it. */
  tried?: true;
  /** Queued at, ms since epoch. */
  at?: number;
}

/** Queued items older than this are dropped unsent. */
export const MAX_AGE_MS = 30 * 86_400_000;

/** Waiting done marks, without queued Undos. */
export function marks(items: readonly OutboxItem[]): OutboxItem[] {
  return items.filter((i) => i.kind !== 'delete');
}

export interface OutboxStore {
  all(user: number): Promise<OutboxItem[]>;
  put(user: number, item: OutboxItem): Promise<void>;
  delete(user: number, uuid: string): Promise<void>;
  clear(user: number): Promise<void>;
}

export interface CacheStore {
  get<T>(key: string): Promise<T | undefined>;
  set(key: string, value: unknown): Promise<void>;
  clearPrefix(prefix: string): Promise<void>;
}

export interface FlushResult {
  sent: number;
  dropped: OutboxItem[];
}

export function memoryStore(): OutboxStore {
  const m = new Map<string, { user: number; item: OutboxItem }>();
  return {
    all: async (user) => [...m.values()].filter((r) => r.user === user).map((r) => ({ ...r.item })),
    put: async (user, i) => void m.set(`${user}:${i.uuid}`, { user, item: { ...i } }),
    delete: async (user, u) => void m.delete(`${user}:${u}`),
    clear: async (user) => {
      for (const [k, r] of m) if (r.user === user) m.delete(k);
    },
  };
}

export function memoryCache(): CacheStore {
  const m = new Map<string, unknown>();
  return {
    get: async <T>(k: string) => structuredClone(m.get(k)) as T | undefined,
    set: async (k, v) => void m.set(k, structuredClone(v)),
    clearPrefix: async (p) => {
      for (const k of [...m.keys()]) if (k.startsWith(p)) m.delete(k);
    },
  };
}

const DB = 'wgc';
const OUTBOX = 'outbox';
const CACHE = 'cache';

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((res, rej) => {
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  dbPromise ??= new Promise((res, rej) => {
    const r = indexedDB.open(DB, 2);
    r.onupgradeneeded = () => {
      const db = r.result;
      for (const n of [...db.objectStoreNames]) db.deleteObjectStore(n);
      db.createObjectStore(OUTBOX, { keyPath: 'key' }).createIndex('user', 'user');
      db.createObjectStore(CACHE);
    };
    r.onsuccess = () => res(r.result);
    r.onerror = () => {
      dbPromise = null;
      rej(r.error);
    };
  });
  return dbPromise;
}

async function tx<T>(store: string, mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb();
  return req(f(db.transaction(store, mode).objectStore(store)));
}

type Row = OutboxItem & { key: string; user: number; seq: number };

export function idbStore(): OutboxStore {
  let n = 0;
  return {
    all: async (user) => {
      const rows = (await tx(OUTBOX, 'readonly', (s) => s.index('user').getAll(user))) as Row[];
      return rows.sort((a, b) => a.seq - b.seq).map(({ key: _k, user: _u, seq: _s, ...i }) => i);
    },
    put: async (user, i) => {
      const key = `${user}:${i.uuid}`;
      const old = (await tx(OUTBOX, 'readonly', (s) => s.get(key))) as Row | undefined;
      const row: Row = { ...i, key, user, seq: old?.seq ?? Date.now() * 1000 + (n++ % 1000) };
      await tx(OUTBOX, 'readwrite', (s) => s.put(row));
    },
    delete: async (user, u) => void (await tx(OUTBOX, 'readwrite', (s) => s.delete(`${user}:${u}`))),
    clear: async (user) => {
      const keys = await tx(OUTBOX, 'readonly', (s) => s.index('user').getAllKeys(user));
      for (const k of keys) await tx(OUTBOX, 'readwrite', (s) => s.delete(k));
    },
  };
}

export function idbCache(): CacheStore {
  return {
    get: async <T>(k: string) => (await tx(CACHE, 'readonly', (s) => s.get(k))) as T | undefined,
    set: async (k, v) => void (await tx(CACHE, 'readwrite', (s) => s.put(v, k))),
    clearPrefix: async (p) => void (await tx(CACHE, 'readwrite', (s) => s.delete(IDBKeyRange.bound(p, p + '￿')))),
  };
}

function rejected(e: unknown): boolean {
  return e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 401 && e.status !== 429;
}

export class Outbox {
  private running: Promise<FlushResult> | null = null;
  private inFlight: string | null = null;
  private locked: Promise<unknown> = Promise.resolve();
  private listeners = new Set<(n: number) => void>();
  private flushListeners = new Set<(r: FlushResult) => void>();

  constructor(
    private store: OutboxStore,
    private user: number,
    private send: (i: OutboxItem) => Promise<void>,
    private now: () => number = Date.now,
  ) {}

  // Store reads and writes that must not interleave with a flush picking the next item.
  private lock<T>(f: () => Promise<T>): Promise<T> {
    const p = this.locked.then(f);
    this.locked = p.catch(() => {});
    return p;
  }

  items(): Promise<OutboxItem[]> {
    return this.store.all(this.user);
  }

  onChange(l: (n: number) => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  onFlushed(l: (r: FlushResult) => void): () => void {
    this.flushListeners.add(l);
    return () => this.flushListeners.delete(l);
  }

  private async changed(): Promise<void> {
    const n = (await this.items()).length;
    for (const l of this.listeners) l(n);
  }

  async add(i: OutboxItem): Promise<void> {
    await this.lock(() => this.store.put(this.user, { ...i, at: i.at ?? this.now() }));
    await this.changed();
  }

  async update(uuid: string, p: { doneOn?: string; note?: string | null }): Promise<boolean> {
    const ok = await this.lock(async () => {
      if (uuid === this.inFlight) return false;
      const cur = (await this.items()).find((i) => i.uuid === uuid);
      if (!cur || cur.tried || cur.kind) return false;
      await this.store.put(this.user, { ...cur, ...p });
      return true;
    });
    if (ok) await this.changed();
    return ok;
  }

  async remove(uuid: string): Promise<'removed' | 'in_flight' | 'absent'> {
    const r = await this.lock(async () => {
      if (uuid === this.inFlight) return 'in_flight' as const;
      if (!(await this.items()).some((i) => i.uuid === uuid)) return 'absent' as const;
      await this.store.delete(this.user, uuid);
      return 'removed' as const;
    });
    if (r === 'removed') await this.changed();
    return r;
  }

  /** Undo: an unsent mark is removed; one the server may have (or an absent one) becomes a queued delete. */
  async cancel(uuid: string, choreId: number): Promise<'removed' | 'in_flight' | 'queued'> {
    const r = await this.lock(async () => {
      if (uuid === this.inFlight) return 'in_flight' as const;
      const cur = (await this.items()).find((i) => i.uuid === uuid);
      if (cur && !cur.kind && !cur.tried) {
        await this.store.delete(this.user, uuid);
        return 'removed' as const;
      }
      if (cur?.kind === 'delete') return 'queued' as const;
      await this.store.put(this.user, { uuid, choreId, doneOn: cur?.doneOn ?? '', note: null, kind: 'delete', at: this.now() });
      return 'queued' as const;
    });
    if (r !== 'in_flight') await this.changed();
    return r;
  }

  async clear(): Promise<void> {
    await this.lock(() => this.store.clear(this.user));
    await this.changed();
  }

  /** Resolves when no send is running. */
  idle(): Promise<unknown> {
    return this.running ?? Promise.resolve();
  }

  flush(): Promise<FlushResult> {
    this.running ??= this.run().finally(() => (this.running = null));
    return this.running;
  }

  private async run(): Promise<FlushResult> {
    const result: FlushResult = { sent: 0, dropped: [] };
    result.dropped = await this.lock(async () => {
      const old = (await this.items()).filter((i) => i.at !== undefined && this.now() - i.at >= MAX_AGE_MS);
      for (const i of old) await this.store.delete(this.user, i.uuid);
      return old;
    });
    const tried = new Set<string>();
    for (;;) {
      const next = await this.lock(async () => {
        const i = (await this.items()).find((x) => !tried.has(x.uuid));
        this.inFlight = i?.uuid ?? null;
        if (i && !i.kind && !i.tried) await this.store.put(this.user, { ...i, tried: true });
        return i;
      });
      if (!next) break;
      tried.add(next.uuid);
      try {
        await this.send(next);
        result.sent++;
        await this.store.delete(this.user, next.uuid);
      } catch (e) {
        if (e instanceof OfflineError || (e instanceof ApiError && e.status === 401)) break;
        if (!rejected(e)) continue;
        result.dropped.push(next);
        await this.store.delete(this.user, next.uuid);
      } finally {
        this.inFlight = null;
      }
    }
    if (result.sent || result.dropped.length) {
      await this.changed();
      for (const l of this.flushListeners) l(result);
    }
    return result;
  }
}
