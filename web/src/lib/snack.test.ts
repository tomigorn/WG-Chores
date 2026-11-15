import { afterEach, describe, expect, it } from 'vitest';
import { choreNames, clearSnack, onSnack, rememberChores, showSnack, toast, watchDropped } from './snack';
import type { FlushResult } from './outbox';

afterEach(() => {
  clearSnack();
  choreNames.clear();
});

describe('snack store', () => {
  it('latest_snack_reaches_listeners_and_clears_by_id', () => {
    const seen: (string | null)[] = [];
    const off = onSnack((s) => seen.push(s?.text ?? null));
    toast('one');
    const id = showSnack({ text: 'two' });
    clearSnack(id - 1);
    clearSnack(id);
    off();
    toast('three');
    expect(seen).toEqual([null, 'one', 'two', null]);
  });

  it('dropped_marks_toast_anywhere_with_known_names', () => {
    let flushed: (r: FlushResult) => void = () => {};
    const outbox = { onFlushed: (l: (r: FlushResult) => void) => ((flushed = l), () => {}) };
    const seen: (string | null)[] = [];
    onSnack((s) => seen.push(s?.text ?? null));
    watchDropped(outbox, () => 'en');
    rememberChores([{ id: 7, name: 'Clean toilet' }]);
    flushed({ sent: 0, dropped: [{ uuid: 'a', choreId: 7, doneOn: '2026-11-14', note: null }] });
    flushed({ sent: 0, dropped: [{ uuid: 'b', choreId: 8, doneOn: '2026-11-14', note: null }] });
    flushed({ sent: 1, dropped: [] });
    flushed({ sent: 0, dropped: [{ uuid: 'c', choreId: 7, doneOn: '', note: null, kind: 'delete' }] });
    expect(seen.slice(1)).toEqual(['Clean toilet could not be saved and was removed from the queue.', 'A done mark could not be saved and was removed from the queue.']);
  });
});
