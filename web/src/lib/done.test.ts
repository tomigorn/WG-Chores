import { describe, expect, it, vi } from 'vitest';
import { ApiError, OfflineError } from './api';
import { Done, newUuid } from './done';
import { Outbox, memoryStore, type OutboxItem } from './outbox';

function setup(online = true) {
  const sent: OutboxItem[] = [];
  const net = { online };
  const offline = () => {
    if (!net.online) throw new OfflineError();
  };
  const api = {
    complete: vi.fn(async (choreId: number, c: { id: string; done_on?: string; note?: string | null }) => {
      offline();
      sent.push({ uuid: c.id, choreId, doneOn: c.done_on!, note: c.note ?? null });
    }),
    deleteCompletion: vi.fn(async (_id: string) => offline()),
    patchCompletion: vi.fn(async (_id: string, _p: { done_on?: string; note?: string | null }) => offline()),
  };
  const outbox = new Outbox(memoryStore(), 1, (i) => api.complete(i.choreId, { id: i.uuid, done_on: i.doneOn, note: i.note }));
  let n = 0;
  const done = new Done(api, outbox, () => `uuid-${++n}`);
  return { api, outbox, done, sent, net };
}

describe('marking done', () => {
  it('mark_sends_completion_with_client_uuid', async () => {
    const { done, sent, outbox } = setup();
    const r = await done.mark(4, '2026-11-14', null);
    expect(r).toEqual({ uuid: 'uuid-1', queued: false });
    expect(sent).toEqual([{ uuid: 'uuid-1', choreId: 4, doneOn: '2026-11-14', note: null }]);
    expect(await outbox.items()).toEqual([]);
  });

  it('mark_offline_is_queued', async () => {
    const { done, outbox } = setup(false);
    expect(await done.mark(4, '2026-11-14', null)).toEqual({ uuid: 'uuid-1', queued: true });
    expect((await outbox.items()).length).toBe(1);
  });

  it('undo_removes_completion', async () => {
    const { done, api, sent, outbox, net } = setup();
    const { uuid } = await done.mark(4, '2026-11-14', null);
    expect(await done.undo(uuid)).toBe('ok');
    expect(api.deleteCompletion).toHaveBeenCalledWith(uuid);

    net.online = false;
    const queued = await done.mark(5, '2026-11-14', null);
    expect(await done.undo(queued.uuid)).toBe('ok');
    expect(await outbox.items()).toEqual([]);
    expect(api.deleteCompletion).toHaveBeenCalledTimes(1);
    net.online = true;
    await outbox.flush();
    expect(sent.map((s) => s.choreId)).toEqual([4]);
  });

  it('undo_waits_for_a_send_in_flight', async () => {
    const { done, api, outbox } = setup();
    let release!: () => void;
    api.complete.mockImplementationOnce(() => new Promise<void>((r) => (release = r)));
    const marking = done.mark(4, '2026-11-14', null);
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    const undoing = done.undo('uuid-1');
    release();
    await marking;
    expect(await undoing).toBe('ok');
    expect(api.deleteCompletion).toHaveBeenCalledWith('uuid-1');
    expect(await outbox.items()).toEqual([]);
  });

  it('offline_edit_shows_connect_message', async () => {
    const { done, api, outbox, net } = setup();
    const { uuid } = await done.mark(4, '2026-11-14', null);
    net.online = false;
    expect(await done.change(uuid, { doneOn: '2026-11-13', note: 'late' })).toBe('offline');
    expect(await done.undo(uuid)).toBe('offline');

    const q = await done.mark(5, '2026-11-14', null);
    expect(await done.change(q.uuid, { doneOn: '2026-11-12', note: null })).toBe('ok');
    expect((await outbox.items())[0]).toMatchObject({ doneOn: '2026-11-12' });
    expect(api.patchCompletion).toHaveBeenCalledTimes(1);
  });

  it('server_errors_are_reported', async () => {
    const { done, api } = setup();
    const { uuid } = await done.mark(4, '2026-11-14', null);
    api.patchCompletion.mockRejectedValueOnce(new ApiError(400, 'future_date', 'x'));
    expect(await done.change(uuid, { doneOn: '2026-11-20', note: null })).toBe('error');
  });
});

describe('client uuid', () => {
  it('uuid_v4_without_secure_context', () => {
    const real = crypto.randomUUID;
    try {
      Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true });
      const a = newUuid();
      expect(a).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
      expect(newUuid()).not.toBe(a);
    } finally {
      Object.defineProperty(crypto, 'randomUUID', { value: real, configurable: true });
    }
  });
});
