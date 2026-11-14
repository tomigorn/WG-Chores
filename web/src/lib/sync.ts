import { api } from './api';
import { Done, type DoneApi } from './done';
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

function browserDeps(): SyncDeps {
  const hasIdb = typeof indexedDB !== 'undefined';
  return {
    store: hasIdb ? fallback(idbStore(), memoryStore()) : memoryStore(),
    cache: hasIdb ? fallback(idbCache(), memoryCache()) : memoryCache(),
    send: async (i) => void (await api.complete(i.choreId, { id: i.uuid, done_on: i.doneOn, note: i.note })),
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
