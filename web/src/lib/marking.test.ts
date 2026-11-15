import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, OfflineError, type Chore } from './api';
import { Done, sendWith } from './done';
import { Outbox, memoryStore } from './outbox';
import { marker } from './marking';
import { clearSnack, onSnack } from './snack';
import type { Snack } from '../components/DoneSnackbar.svelte';

const rafi = { id: 2, display_name: 'Rafi', former: false };
const anna = { id: 1, display_name: 'Anna', former: false };
const toilet: Chore = {
  id: 7,
  household_id: 1,
  name: 'Clean toilet',
  category: null,
  interval: '1w',
  archived: false,
  last: { done_on: '2026-10-19', by: rafi },
  days_since: 26,
  due_level: 'long_overdue',
  days_overdue: 19,
  usual_interval: null,
};

function setup() {
  let clock = 1_000_000;
  const net = { online: true };
  const api = {
    complete: vi.fn(async (_c: number, _b: { id: string }) => {
      if (!net.online) throw new OfflineError();
    }),
    deleteCompletion: vi.fn(async (_id: string) => {
      if (!net.online) throw new OfflineError();
    }),
    patchCompletion: vi.fn(async () => {}),
  };
  const outbox = new Outbox(memoryStore(), 1, sendWith(api));
  let n = 0;
  const done = new Done(api, outbox, () => `uuid-${++n}`);
  let row: Chore = toilet;
  const reload = vi.fn();
  let snack: Snack | null = null;
  const off = onSnack((s) => (snack = s));
  const m = marker({
    done,
    tz: 'UTC',
    me: () => anna,
    lang: () => 'en',
    get: () => row,
    set: (c) => (row = c),
    reload,
    now: () => clock,
  });
  return {
    api,
    outbox,
    m,
    net,
    reload,
    row: () => row,
    snack: () => snack,
    tick: (ms: number) => (clock += ms),
    off,
  };
}

let cleanup: (() => void) | null = null;

afterEach(() => {
  cleanup?.();
  cleanup = null;
  clearSnack();
});

describe('marking done', () => {
  it('double_tap_marks_once', async () => {
    const s = setup();
    cleanup = s.off;
    let release!: () => void;
    s.api.complete.mockImplementationOnce(() => new Promise<void>((r) => (release = () => r())));
    const first = s.m.tap(toilet);
    s.tick(200);
    await s.m.tap(toilet);
    await vi.waitFor(() => expect(release).toBeTypeOf('function'));
    s.tick(2000);
    await s.m.tap(toilet);
    release();
    await first;
    expect(s.api.complete).toHaveBeenCalledTimes(1);

    s.tick(500);
    await s.m.tap(toilet);
    expect(s.api.complete).toHaveBeenCalledTimes(1);
    s.tick(600);
    await s.m.tap(toilet);
    expect(s.api.complete).toHaveBeenCalledTimes(2);
  });

  it('mark_is_optimistic_and_undo_restores', async () => {
    const s = setup();
    cleanup = s.off;
    await s.m.tap(toilet);
    expect(s.row().last).toEqual({ done_on: expect.any(String), by: anna });
    const undo = s.snack()!.actions!.find((a) => a.label === 'Undo')!;
    undo.run();
    expect(s.row()).toBe(toilet);
    await vi.waitFor(() => expect(s.reload).toHaveBeenCalled());
    expect(s.api.deleteCompletion).toHaveBeenCalledWith('uuid-1');
  });

  it('undo_while_offline_stays_undone_and_is_queued', async () => {
    const s = setup();
    cleanup = s.off;
    await s.m.tap(toilet);
    s.net.online = false;
    await s.m.undo(toilet, 'uuid-1', 7);
    expect(s.row()).toBe(toilet);
    expect(await s.outbox.items()).toEqual([expect.objectContaining({ uuid: 'uuid-1', kind: 'delete' })]);
  });

  it('undo_refused_restores_the_mark_and_says_so', async () => {
    const s = setup();
    cleanup = s.off;
    await s.m.tap(toilet);
    const marked = s.row();
    s.api.deleteCompletion.mockRejectedValueOnce(new ApiError(403, 'forbidden', 'x'));
    await s.m.undo(toilet, 'uuid-1', 7);
    expect(s.row()).toBe(marked);
    expect(s.snack()!.text).toBe('Could not save. Please try again.');
  });

  it('rejected_mark_reverts_the_row', async () => {
    const s = setup();
    cleanup = s.off;
    s.api.complete.mockRejectedValueOnce(new ApiError(404, 'not_found', 'gone'));
    await s.m.tap(toilet);
    expect(s.row()).toBe(toilet);
  });
});
