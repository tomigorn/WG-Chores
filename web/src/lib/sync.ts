import { api } from './api';
import { Done } from './done';
import { Outbox, idbStore, memoryStore, type OutboxStore } from './outbox';

function store(): OutboxStore {
  const mem = memoryStore();
  if (typeof indexedDB === 'undefined') return mem;
  const idb = idbStore();
  let broken = false;
  const pick = async <T>(f: (s: OutboxStore) => Promise<T>): Promise<T> => {
    if (!broken) {
      try {
        return await f(idb);
      } catch {
        broken = true;
      }
    }
    return f(mem);
  };
  return { all: () => pick((s) => s.all()), put: (i) => pick((s) => s.put(i)), delete: (u) => pick((s) => s.delete(u)) };
}

export const outbox = new Outbox(store(), async (i) => {
  await api.complete(i.choreId, { id: i.uuid, done_on: i.doneOn, note: i.note });
});

export const done = new Done(api, outbox);
