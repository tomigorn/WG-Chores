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
    const first = new Outbox(store, 1, offline);
    await first.add(item('a'));
    await first.add(item('b', 2));
    expect((await first.flush()).sent).toBe(0);
    expect(offline).toHaveBeenCalledTimes(1);

    const send = vi.fn(async () => {});
    const reloaded = new Outbox(store, 1, send);
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
    const box = new Outbox(store, 1, send);
    for (const u of ['gone', 'dup', 'boom', 'ok']) await box.add(item(u));
    const r = await box.flush();
    expect(r.dropped.map((i) => i.uuid)).toEqual(['gone', 'dup']);
    expect((await box.items()).map((i) => i.uuid)).toEqual(['boom']);
  });

  it('remove_and_update_waiting_items', async () => {
    const box = new Outbox(memoryStore(), 1, async () => {
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
    const box = new Outbox(memoryStore(), 1, () => new Promise<void>((r) => (release = r)));
    await box.add(item('a'));
    const f = box.flush();
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    expect(await box.remove('a')).toBe('in_flight');
    expect(await box.update('a', { doneOn: '2026-11-13' })).toBe(false);
    release();
    await f;
    expect(await box.items()).toEqual([]);
  });

  it('outbox_is_per_user', async () => {
    const store = memoryStore();
    const sendA = vi.fn(async () => {
      throw new OfflineError();
    });
    const a = new Outbox(store, 1, sendA);
    await a.add(item('a'));
    const sendB = vi.fn(async () => {});
    const b = new Outbox(store, 2, sendB);
    expect(await b.items()).toEqual([]);
    await b.flush();
    expect(sendB).not.toHaveBeenCalled();
    expect(await b.remove('a')).toBe('absent');
    await b.clear();
    expect((await a.items()).length).toBe(1);
    await a.clear();
    expect(await a.items()).toEqual([]);
  });

  it('flush_results_reach_listeners_whoever_flushes', async () => {
    const box = new Outbox(memoryStore(), 1, async (i) => {
      if (i.uuid === 'bad') throw new ApiError(404, 'not_found', 'gone');
    });
    const seen: { sent: number; dropped: string[] }[] = [];
    box.onFlushed((r) => seen.push({ sent: r.sent, dropped: r.dropped.map((i) => i.uuid) }));
    await box.add(item('bad', 3));
    await box.add(item('ok'));
    await box.flush();
    await box.flush();
    expect(seen).toEqual([{ sent: 1, dropped: ['bad'] }]);
  });

  it('update_never_lost_to_a_concurrent_flush', async () => {
    const base = memoryStore();
    let armed = false;
    let release!: () => void;
    const store = {
      ...base,
      all: async (u: number) => {
        const r = await base.all(u);
        if (armed) {
          armed = false;
          await new Promise<void>((res) => (release = res));
        }
        return r;
      },
    };
    const sent: OutboxItem[] = [];
    const box = new Outbox(store, 1, async (i) => void sent.push(i));
    await box.add(item('a'));
    armed = true;
    const updating = box.update('a', { doneOn: '2026-11-10' });
    const flushing = box.flush();
    await new Promise((r) => setTimeout(r, 5));
    release();
    const [updated] = await Promise.all([updating, flushing]);
    await box.flush();
    expect(sent.length).toBe(1);
    expect(sent[0]!.doneOn).toBe(updated ? '2026-11-10' : '2026-11-14');
  });
});
