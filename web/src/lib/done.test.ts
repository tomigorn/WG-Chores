import { describe, expect, it, vi } from 'vitest';
import { ApiError, OfflineError } from './api';
import { Done, newUuid, sendWith } from './done';
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
  const outbox = new Outbox(memoryStore(), 1, sendWith(api));
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
    expect(await done.undo(uuid, 4)).toBe('ok');
    expect(api.deleteCompletion).toHaveBeenCalledWith(uuid);
    expect(await outbox.items()).toEqual([]);

    net.online = false;
    await done.mark(5, '2026-11-14', null);
    const waiting = await done.mark(6, '2026-11-14', null);
    expect(await done.undo(waiting.uuid, 6)).toBe('ok');
    expect((await outbox.items()).map((i) => i.choreId)).toEqual([5]);
    expect(api.deleteCompletion).toHaveBeenCalledTimes(1);
    net.online = true;
    await outbox.flush();
    expect(sent.map((s) => s.choreId)).toEqual([4, 5]);
  });

  it('undo_of_a_mark_that_may_have_reached_the_server_is_queued_and_retried', async () => {
    const { done, api, sent, outbox, net } = setup(false);
    const { uuid } = await done.mark(4, '2026-11-14', null);
    expect(await done.undo(uuid, 4)).toBe('queued');
    expect(await outbox.items()).toEqual([expect.objectContaining({ uuid, kind: 'delete' })]);
    net.online = true;
    api.deleteCompletion.mockRejectedValueOnce(new ApiError(404, 'not_found', 'x'));
    const r = await outbox.flush();
    expect(r).toEqual({ sent: 1, dropped: [] });
    expect(api.deleteCompletion).toHaveBeenCalledWith(uuid);
    expect(sent).toEqual([]);
    expect(await outbox.items()).toEqual([]);
  });

  it('queued_mark_whose_entry_was_undone_elsewhere_is_settled', async () => {
    const { done, api, outbox, net } = setup(false);
    await done.mark(4, '2026-11-14', null);
    net.online = true;
    api.complete.mockRejectedValueOnce(new ApiError(410, 'deleted', 'x'));
    expect(await outbox.flush()).toEqual({ sent: 1, dropped: [] });
    expect(await outbox.items()).toEqual([]);
  });

  it('undo_after_a_sent_mark_survives_an_offline_moment', async () => {
    const { done, api, outbox, net } = setup();
    const { uuid } = await done.mark(4, '2026-11-14', null);
    net.online = false;
    expect(await done.undo(uuid, 4)).toBe('queued');
    const store = outbox.items();
    expect(await store).toEqual([expect.objectContaining({ uuid, kind: 'delete', choreId: 4 })]);
    net.online = true;
    await outbox.flush();
    expect(api.deleteCompletion).toHaveBeenLastCalledWith(uuid);
    expect(await outbox.items()).toEqual([]);
  });

  it('undo_during_a_send_that_fails_removes_it_from_the_outbox', async () => {
    const { done, api, sent, outbox, net } = setup();
    let fail!: () => void;
    api.complete.mockImplementationOnce(() => new Promise<void>((_r, rej) => (fail = () => rej(new OfflineError()))));
    const marking = done.mark(4, '2026-11-14', null);
    await vi.waitFor(() => expect(fail).toBeTypeOf('function'));
    net.online = false;
    const undoing = done.undo('uuid-1', 4);
    fail();
    await marking;
    expect(await undoing).toBe('queued');
    net.online = true;
    await outbox.flush();
    expect(sent).toEqual([]);
    expect(api.deleteCompletion).toHaveBeenLastCalledWith('uuid-1');
    expect(await outbox.items()).toEqual([]);
  });

  it('undo_refused_by_the_server_is_an_error', async () => {
    const { done, api, outbox } = setup();
    const { uuid } = await done.mark(4, '2026-11-14', null);
    api.deleteCompletion.mockRejectedValueOnce(new ApiError(403, 'forbidden', 'x'));
    expect(await done.undo(uuid, 4)).toBe('error');
    expect(await outbox.items()).toEqual([]);
  });

  it('undo_waits_for_a_send_in_flight', async () => {
    const { done, api, outbox } = setup();
    let release!: () => void;
    api.complete.mockImplementationOnce(() => new Promise<void>((r) => (release = r)));
    const marking = done.mark(4, '2026-11-14', null);
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    const undoing = done.undo('uuid-1', 4);
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
    expect(await done.undo(uuid, 4)).toBe('queued');

    const q = await done.mark(5, '2026-11-14', null);
    expect(await done.change(q.uuid, { doneOn: '2026-11-12', note: null })).toBe('ok');
    expect((await outbox.items()).find((i) => i.uuid === q.uuid)).toMatchObject({ doneOn: '2026-11-12' });
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

  it('tried_mark_edit_goes_to_patch_not_local_rewrite', async () => {
    const { done, api, outbox, net } = setup(false);
    const { uuid } = await done.mark(4, '2026-11-14', null);
    expect(await outbox.items()).toEqual([expect.objectContaining({ uuid, tried: true })]);
    net.online = true;
    expect(await done.change(uuid, { doneOn: '2026-11-10', note: 'x' })).toBe('ok');
    expect(api.patchCompletion).toHaveBeenCalledWith(uuid, { done_on: '2026-11-10', note: 'x' });
    expect((await outbox.items())[0]).toMatchObject({ doneOn: '2026-11-14', note: null });
    net.online = false;
    expect(await done.change(uuid, { doneOn: '2026-11-10', note: 'x' })).toBe('offline');
  });
});
