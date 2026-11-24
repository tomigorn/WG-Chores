import type { Snack } from '../components/DoneSnackbar.svelte';
import type { Lang } from './i18n';
import { marks, type FlushResult } from './outbox';
import { droppedMessage, droppedUndoMessage } from './words';

type Listener = (s: Snack | null) => void;

let current: Snack | null = null;
let seq = 0;
const listeners = new Set<Listener>();

function emit(): void {
  for (const l of listeners) l(current);
}

export function showSnack(s: Omit<Snack, 'id'>): number {
  current = { ...s, id: ++seq };
  emit();
  return seq;
}

export function toast(text: string, duration?: number): number {
  return showSnack(duration ? { text, duration } : { text });
}

/** Clears the snack, or only the given one if it is still showing. */
export function clearSnack(id?: number): void {
  if (!current || (id !== undefined && current.id !== id)) return;
  current = null;
  emit();
}

export function onSnack(l: Listener): () => void {
  listeners.add(l);
  l(current);
  return () => listeners.delete(l);
}

export const choreNames = new Map<number, string>();

export function rememberChores(list: readonly { id: number; name: string }[]): void {
  for (const c of list) choreNames.set(c.id, c.name);
}

export function watchDropped(outbox: { onFlushed: (l: (r: FlushResult) => void) => () => void }, lang: () => Lang): () => void {
  return outbox.onFlushed((r) => {
    const m = droppedMessage(marks(r.dropped), choreNames, lang());
    if (m) toast(m);
    const u = droppedUndoMessage(r.dropped.filter((i) => i.kind === 'delete'), choreNames, lang());
    if (u) toast(u);
  });
}
