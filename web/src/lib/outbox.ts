import { ApiError, OfflineError } from './api';

export interface OutboxItem {
  uuid: string;
  choreId: number;
  doneOn: string;
  note: string | null;
}

export interface OutboxStore {
  all(): Promise<OutboxItem[]>;
  put(item: OutboxItem): Promise<void>;
  delete(uuid: string): Promise<void>;
}

export interface FlushResult {
  sent: number;
  dropped: OutboxItem[];
}

export function memoryStore(): OutboxStore {
  const m = new Map<string, OutboxItem>();
  return {
    all: async () => [...m.values()].map((i) => ({ ...i })),
    put: async (i) => void m.set(i.uuid, { ...i }),
    delete: async (u) => void m.delete(u),
  };
}

const DB = 'wgc';
const STORE = 'outbox';

function req<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((res, rej) => {
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}

let dbPromise: Promise<IDBDatabase> | null = null;

export function openDb(): Promise<IDBDatabase> {
  dbPromise ??= new Promise((res, rej) => {
    const r = indexedDB.open(DB, 1);
    r.onupgradeneeded = () => {
      r.result.createObjectStore(STORE, { keyPath: 'uuid' });
      r.result.createObjectStore('cache');
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

export function idbStore(): OutboxStore {
  let seq = 0;
  return {
    all: async () => {
      const rows = (await tx(STORE, 'readonly', (s) => s.getAll())) as (OutboxItem & { seq: number })[];
      return rows.sort((a, b) => a.seq - b.seq).map(({ uuid, choreId, doneOn, note }) => ({ uuid, choreId, doneOn, note }));
    },
    put: async (i) => {
      const old = (await tx(STORE, 'readonly', (s) => s.get(i.uuid))) as { seq: number } | undefined;
      await tx(STORE, 'readwrite', (s) => s.put({ ...i, seq: old?.seq ?? Date.now() * 1000 + (seq++ % 1000) }));
    },
    delete: async (u) => void (await tx(STORE, 'readwrite', (s) => s.delete(u))),
  };
}

export async function cacheGet<T>(key: string): Promise<T | undefined> {
  try {
    return (await tx('cache', 'readonly', (s) => s.get(key))) as T | undefined;
  } catch {
    return undefined;
  }
}

export async function cacheSet(key: string, value: unknown): Promise<void> {
  try {
    await tx('cache', 'readwrite', (s) => s.put(value, key));
  } catch {
    /* storage unavailable */
  }
}

function rejected(e: unknown): boolean {
  return e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 401 && e.status !== 429;
}

export class Outbox {
  private running: Promise<FlushResult> | null = null;
  private inFlight: string | null = null;
  private listeners = new Set<(n: number) => void>();

  constructor(
    private store: OutboxStore,
    private send: (i: OutboxItem) => Promise<void>,
  ) {}

  items(): Promise<OutboxItem[]> {
    return this.store.all();
  }

  onChange(l: (n: number) => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  private async changed(): Promise<void> {
    const n = (await this.store.all()).length;
    for (const l of this.listeners) l(n);
  }

  async add(i: OutboxItem): Promise<void> {
    await this.store.put(i);
    await this.changed();
  }

  async update(uuid: string, p: { doneOn?: string; note?: string | null }): Promise<boolean> {
    if (uuid === this.inFlight) return false;
    const cur = (await this.store.all()).find((i) => i.uuid === uuid);
    if (!cur) return false;
    await this.store.put({ ...cur, ...p });
    await this.changed();
    return true;
  }

  async remove(uuid: string): Promise<'removed' | 'in_flight' | 'absent'> {
    if (uuid === this.inFlight) return 'in_flight';
    if (!(await this.store.all()).some((i) => i.uuid === uuid)) return 'absent';
    await this.store.delete(uuid);
    await this.changed();
    return 'removed';
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
    const tried = new Set<string>();
    for (;;) {
      const next = (await this.store.all()).find((i) => !tried.has(i.uuid));
      if (!next) break;
      tried.add(next.uuid);
      this.inFlight = next.uuid;
      try {
        await this.send(next);
        result.sent++;
        await this.store.delete(next.uuid);
      } catch (e) {
        if (e instanceof OfflineError || (e instanceof ApiError && e.status === 401)) break;
        if (!rejected(e)) continue;
        result.dropped.push(next);
        await this.store.delete(next.uuid);
      } finally {
        this.inFlight = null;
      }
    }
    if (result.sent || result.dropped.length) await this.changed();
    return result;
  }
}
