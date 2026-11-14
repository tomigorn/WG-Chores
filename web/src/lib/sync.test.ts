import { describe, expect, it, vi } from 'vitest';
import { OfflineError } from './api';
import { memoryCache, memoryStore, type OutboxItem } from './outbox';
import { Sync } from './sync';

function deps(online = { v: true }) {
  const sent: OutboxItem[] = [];
  const send = vi.fn(async (i: OutboxItem) => {
    if (!online.v) throw new OfflineError();
    sent.push(i);
  });
  const api = { deleteCompletion: vi.fn(async () => {}), patchCompletion: vi.fn(async () => {}) };
  return { store: memoryStore(), cache: memoryCache(), send, api, events: new EventTarget(), sent, online };
}

describe('sync per user', () => {
  it('outbox_and_cache_are_per_user', async () => {
    const d = deps({ v: false });
    const a = new Sync(1, d);
    await a.done.mark(5, '2026-11-14', null);
    await a.setList(9, { secret: 'list' });
    const b = new Sync(2, d);
    expect(await b.outbox.items()).toEqual([]);
    expect(await b.getList(9)).toBeUndefined();
    expect(await a.getList(9)).toEqual({ secret: 'list' });
  });

  it('logout_flushes_then_reports_unsynced', async () => {
    const d = deps({ v: false });
    const a = new Sync(1, d);
    await a.done.mark(5, '2026-11-14', null);
    await a.setList(9, { x: 1 });
    const b = new Sync(2, d);
    await b.setList(9, { y: 2 });
    expect(await a.prepareLogout()).toBe(1);
    d.online.v = true;
    expect(await a.prepareLogout()).toBe(0);
    expect(d.sent.length).toBe(1);
    await a.discard();
    expect(await a.getList(9)).toBeUndefined();
    expect(await b.getList(9)).toEqual({ y: 2 });
  });

  it('discard_drops_unsynced_marks', async () => {
    const d = deps({ v: false });
    const a = new Sync(1, d);
    await a.done.mark(5, '2026-11-14', null);
    await a.discard();
    expect(await a.outbox.items()).toEqual([]);
  });

  it('flush_on_start_and_online', async () => {
    const d = deps({ v: false });
    const first = new Sync(1, d);
    await first.done.mark(5, '2026-11-14', null);
    d.online.v = true;
    const s = new Sync(1, d);
    const stop = s.start();
    await vi.waitFor(() => expect(d.sent.length).toBe(1));
    d.online.v = false;
    await s.done.mark(6, '2026-11-14', null);
    d.online.v = true;
    d.events.dispatchEvent(new Event('online'));
    await vi.waitFor(() => expect(d.sent.map((i) => i.choreId)).toEqual([5, 6]));
    stop();
    d.online.v = false;
    await s.done.mark(7, '2026-11-14', null);
    d.online.v = true;
    d.events.dispatchEvent(new Event('online'));
    await new Promise((r) => setTimeout(r, 10));
    expect(d.sent.length).toBe(2);
  });
});
