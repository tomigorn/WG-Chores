import { describe, expect, it, vi } from 'vitest';
import { ApiError, OfflineError } from './api';
import { Outbox, memoryStore, type OutboxItem } from './outbox';

const item = (uuid: string, choreId = 1): OutboxItem => ({ uuid, choreId, doneOn: '2026-11-14', note: null });

describe('outbox', () => {
  it('outbox_survives_reload_and_flushes_once', async () => {
    const store = memoryStore();
    const offline = vi.fn(async () => {
      throw new OfflineError();
    });
    const first = new Outbox(store, offline);
    await first.add(item('a'));
    await first.add(item('b', 2));
    expect((await first.flush()).sent).toBe(0);
    expect(offline).toHaveBeenCalledTimes(1);

    const send = vi.fn(async () => {});
    const reloaded = new Outbox(store, send);
    expect((await reloaded.items()).map((i) => i.uuid)).toEqual(['a', 'b']);
    const [r1, r2] = await Promise.all([reloaded.flush(), reloaded.flush()]);
    expect(r1).toBe(r2);
    expect(r1.sent).toBe(2);
    expect(send).toHaveBeenCalledTimes(2);
    expect(await reloaded.items()).toEqual([]);
    await reloaded.flush();
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('rejected_items_are_dropped_server_errors_kept', async () => {
    const store = memoryStore();
    const send = vi.fn(async (i: OutboxItem) => {
      if (i.uuid === 'gone') throw new ApiError(404, 'not_found', 'gone');
      if (i.uuid === 'dup') throw new ApiError(409, 'id_in_use', 'dup');
      if (i.uuid === 'boom') throw new ApiError(500, 'internal', 'boom');
    });
    const box = new Outbox(store, send);
    for (const u of ['gone', 'dup', 'boom', 'ok']) await box.add(item(u));
    const r = await box.flush();
    expect(r.dropped.map((i) => i.uuid)).toEqual(['gone', 'dup']);
    expect((await box.items()).map((i) => i.uuid)).toEqual(['boom']);
  });

  it('remove_and_update_waiting_items', async () => {
    const box = new Outbox(memoryStore(), async () => {
      throw new OfflineError();
    });
    const seen: number[] = [];
    box.onChange((n) => seen.push(n));
    await box.add(item('a'));
    expect(await box.update('a', { doneOn: '2026-11-12', note: 'late' })).toBe(true);
    expect(await box.items()).toEqual([{ uuid: 'a', choreId: 1, doneOn: '2026-11-12', note: 'late' }]);
    expect(await box.remove('a')).toBe('removed');
    expect(await box.remove('a')).toBe('absent');
    expect(await box.update('a', { doneOn: '2026-11-12' })).toBe(false);
    expect(seen).toEqual([1, 1, 0]);
  });

  it('item_being_sent_is_reported_in_flight', async () => {
    let release!: () => void;
    const box = new Outbox(memoryStore(), () => new Promise<void>((r) => (release = r)));
    await box.add(item('a'));
    const f = box.flush();
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    expect(await box.remove('a')).toBe('in_flight');
    expect(await box.update('a', { doneOn: '2026-11-13' })).toBe(false);
    release();
    await f;
    expect(await box.items()).toEqual([]);
  });
});
