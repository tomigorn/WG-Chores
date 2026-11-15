import { api } from './api';
import { Done, sendWith, type DoneApi } from './done';
import { Outbox, idbCache, idbStore, memoryCache, memoryStore, type CacheStore, type OutboxItem, type OutboxStore } from './outbox';

export interface SyncDeps {
  store: OutboxStore;
  cache: CacheStore;
  send: (i: OutboxItem) => Promise<void>;
  api: DoneApi;
  events: EventTarget;
}

export class Sync {
  readonly outbox: Outbox;
  readonly done: Done;

  constructor(
    readonly user: number,
    private deps: SyncDeps,
  ) {
    this.outbox = new Outbox(deps.store, user, deps.send);
    this.done = new Done(deps.api, this.outbox);
  }

  /** Flushes now and whenever the browser comes back online; returns the stop function. */
  start(): () => void {
    const online = () => void this.outbox.flush();
    this.deps.events.addEventListener('online', online);
    online();
    return () => this.deps.events.removeEventListener('online', online);
  }

  private key(hid: number): string {
    return `${this.user}:household:${hid}`;
  }

  getCached<T>(key: string): Promise<T | undefined> {
    return this.deps.cache.get<T>(`${this.user}:${key}`).catch(() => undefined);
  }

  setCached(key: string, v: unknown): Promise<void> {
    return this.deps.cache.set(`${this.user}:${key}`, v).catch(() => {});
  }

  getList<T>(hid: number): Promise<T | undefined> {
    return this.deps.cache.get<T>(this.key(hid)).catch(() => undefined);
  }

  setList(hid: number, v: unknown): Promise<void> {
    return this.deps.cache.set(this.key(hid), v).catch(() => {});
  }

  /** Tries to send what is waiting; returns how many marks would be lost by signing out now. */
  async prepareLogout(): Promise<number> {
    await this.outbox.idle();
    await this.outbox.flush();
    return (await this.outbox.items()).length;
  }

  /** Login ended without sign-out: cached lists go, waiting marks stay for the same user's next sign-in. */
  async clearCache(): Promise<void> {
    await this.deps.cache.clearPrefix(`${this.user}:`).catch(() => {});
  }

  async discard(): Promise<void> {
    await this.outbox.clear();
    await this.deps.cache.clearPrefix(`${this.user}:`).catch(() => {});
  }
}

function fallback<T extends object>(primary: T, backup: T): T {
  let broken = false;
  return new Proxy(primary, {
    get(_t, prop) {
      return async (...args: unknown[]) => {
        if (!broken) {
          try {
            return await (primary as Record<string | symbol, (...a: unknown[]) => Promise<unknown>>)[prop]!(...args);
          } catch {
            broken = true;
          }
        }
        return (backup as Record<string | symbol, (...a: unknown[]) => Promise<unknown>>)[prop]!(...args);
      };
    },
  });
}

// one set of stores per page, so a restarted sync or a cleanup sees the same data
let stores: Pick<SyncDeps, 'store' | 'cache'> | null = null;

function browserStores(): Pick<SyncDeps, 'store' | 'cache'> {
  const hasIdb = typeof indexedDB !== 'undefined';
  stores ??= {
    store: hasIdb ? fallback(idbStore(), memoryStore()) : memoryStore(),
    cache: hasIdb ? fallback(idbCache(), memoryCache()) : memoryCache(),
  };
  return stores;
}

/** Login ended for a user whose sync is not running: drops their cached lists, keeps waiting marks. */
export async function clearUserCache(user: number): Promise<void> {
  await browserStores().cache.clearPrefix(`${user}:`).catch(() => {});
}

function browserDeps(): SyncDeps {
  return {
    ...browserStores(),
    send: sendWith(api),
    api,
    events: globalThis as unknown as EventTarget,
  };
}

let current: { sync: Sync; stop: () => void } | null = null;

export function startSync(user: number): Sync {
  if (current?.sync.user === user) return current.sync;
  current?.stop();
  const sync = new Sync(user, browserDeps());
  current = { sync, stop: sync.start() };
  return sync;
}

export function stopSync(): void {
  current?.stop();
  current = null;
}

export function sync(): Sync {
  if (!current) throw new Error('sync not started');
  return current.sync;
}
