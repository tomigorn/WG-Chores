import { ApiError, OfflineError } from './api';
import type { Outbox, OutboxItem } from './outbox';

export interface DoneApi {
  deleteCompletion(id: string): Promise<unknown>;
  patchCompletion(id: string, p: { done_on?: string; note?: string | null }): Promise<unknown>;
}

export type EditResult = 'ok' | 'offline' | 'error';
/** 'queued': the Undo waits in the outbox and is sent once the server answers. */
export type UndoResult = 'ok' | 'queued' | 'error';

/** Sends one outbox item; a delete the server no longer knows and a mark undone since (410) count as done. */
export function sendWith(api: {
  complete(choreId: number, c: { id: string; done_on?: string; note?: string | null }): Promise<unknown>;
  deleteCompletion(id: string): Promise<unknown>;
}): (i: OutboxItem) => Promise<void> {
  return async (i) => {
    const settled = i.kind === 'delete' ? 404 : 410;
    try {
      if (i.kind === 'delete') await api.deleteCompletion(i.uuid);
      else await api.complete(i.choreId, { id: i.uuid, done_on: i.doneOn, note: i.note });
    } catch (e) {
      if (!(e instanceof ApiError && e.status === settled)) throw e;
    }
  };
}

export function newUuid(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = (b[6]! & 0x0f) | 0x40;
  b[8] = (b[8]! & 0x3f) | 0x80;
  const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

export class Done {
  constructor(
    private api: DoneApi,
    private outbox: Outbox,
    private uuid: () => string = newUuid,
  ) {}

  newId(): string {
    return this.uuid();
  }

  async mark(choreId: number, doneOn: string, note: string | null, uuid = this.uuid()): Promise<{ uuid: string; queued: boolean; rejected?: true }> {
    await this.outbox.add({ uuid, choreId, doneOn, note });
    await this.outbox.idle();
    const r = await this.outbox.flush();
    if (r.dropped.some((i) => i.uuid === uuid)) return { uuid, queued: false, rejected: true };
    const queued = (await this.outbox.items()).some((i) => i.uuid === uuid);
    return { uuid, queued };
  }

  async undo(uuid: string, choreId: number): Promise<UndoResult> {
    let r = await this.outbox.cancel(uuid, choreId);
    if (r === 'in_flight') {
      await this.outbox.idle();
      r = await this.outbox.cancel(uuid, choreId);
    }
    if (r === 'removed') return 'ok';
    await this.outbox.idle();
    const f = await this.outbox.flush();
    if (f.dropped.some((i) => i.uuid === uuid)) return 'error';
    return (await this.outbox.items()).some((i) => i.uuid === uuid) ? 'queued' : 'ok';
  }

  async change(uuid: string, p: { doneOn: string; note: string | null }): Promise<EditResult> {
    if (await this.outbox.update(uuid, p)) return 'ok';
    await this.outbox.idle();
    if (await this.outbox.update(uuid, p)) return 'ok';
    try {
      await this.api.patchCompletion(uuid, { done_on: p.doneOn, note: p.note });
      return 'ok';
    } catch (e) {
      return e instanceof OfflineError ? 'offline' : 'error';
    }
  }
}
