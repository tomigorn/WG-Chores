import type { Snack } from '../components/DoneSnackbar.svelte';
import type { By, Chore } from './api';
import { applyCompletion, doneWhen, todayIn } from './chores';
import type { Done } from './done';
import { t, type Lang } from './i18n';
import type { OutboxItem } from './outbox';
import { clearSnack, showSnack, toast } from './snack';

type Action = NonNullable<Snack['actions']>[number];

/** A second tap on the same chore within this time (or while its mark is being sent) is ignored. */
export const TAP_GUARD_MS = 1000;

export interface MarkerOptions {
  done: Done;
  tz: string;
  me: () => By;
  lang: () => Lang;
  /** The chore as shown now. */
  get: (id: number) => Chore | undefined;
  /** Shows a changed chore at once. */
  set: (c: Chore) => void;
  reload: () => unknown;
  setToday?: (today: string) => void;
  /** A mark was added to or taken out of the waiting list on screen. */
  pending?: (p: { add: OutboxItem } | { remove: string }) => void;
  /** The outbox settled after a mark or an undo. */
  settled?: () => unknown;
  /** More snack actions after Undo. */
  more?: (c: Chore, prev: Chore, uuid: string, doneOn: string, note: string | null) => Action[];
  now?: () => number;
}

/** Optimistic done marks with the Undo snack, shared by the household list and the chore page. */
export function marker(o: MarkerOptions) {
  const now = o.now ?? Date.now;
  const sending = new Set<number>();
  const lastTap = new Map<number, number>();

  function today(): string {
    const d = todayIn(o.tz);
    o.setToday?.(d);
    return d;
  }

  function snack(c: Chore, prev: Chore, uuid: string, doneOn: string, note: string | null, more = true): void {
    showSnack({
      icon: 'check',
      strong: c.name,
      text: t('done.snack', { when: doneWhen(doneOn, today(), o.lang()) }),
      actions: [{ label: t('done.undo'), run: () => void undo(prev, uuid, c.id) }, ...(more && o.more ? o.more(c, prev, uuid, doneOn, note) : [])],
    });
  }

  async function mark(c: Chore, doneOn: string | null, note: string | null): Promise<void> {
    const day = today();
    doneOn ??= day;
    const prev = o.get(c.id) ?? c;
    const uuid = o.done.newId();
    o.set(applyCompletion(prev, { doneOn, by: o.me() }, day));
    o.pending?.({ add: { uuid, choreId: c.id, doneOn, note } });
    snack(c, prev, uuid, doneOn, note);
    const r = await o.done.mark(c.id, doneOn, note, uuid);
    await o.settled?.();
    if (r.rejected) o.set(prev);
  }

  async function tap(c: Chore): Promise<void> {
    if (sending.has(c.id) || now() - (lastTap.get(c.id) ?? -Infinity) < TAP_GUARD_MS) return;
    sending.add(c.id);
    lastTap.set(c.id, now());
    try {
      await mark(c, null, null);
    } finally {
      sending.delete(c.id);
      lastTap.set(c.id, now());
    }
  }

  async function undo(prev: Chore, uuid: string, choreId: number): Promise<void> {
    clearSnack();
    const before = o.get(choreId);
    o.set(prev);
    o.pending?.({ remove: uuid });
    const r = await o.done.undo(uuid, choreId);
    await o.settled?.();
    if (r === 'ok') return void o.reload();
    if (r === 'queued') return;
    if (before) o.set(before);
    toast(t('done.failed'));
  }

  return { tap, mark, undo, snack };
}
