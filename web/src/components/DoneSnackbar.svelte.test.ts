// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DoneSnackbar, { type Snack } from './DoneSnackbar.svelte';

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('DoneSnackbar', () => {
  it('timer_restarts_after_an_action_removed_a_hovered_snack', () => {
    const onexpire = vi.fn();
    const props = $state({ snack: { id: 1, text: 'done', actions: [{ label: 'Undo', run: () => (props.snack = null) }] } as Snack | null, onexpire });
    const app = mount(DoneSnackbar, { target: document.body, props });
    flushSync();
    const el = document.querySelector('.snack')!;
    el.dispatchEvent(new Event('pointerenter'));
    flushSync();
    vi.advanceTimersByTime(7000);
    expect(onexpire).not.toHaveBeenCalled();
    (document.querySelector('.act') as HTMLButtonElement).click();
    flushSync();
    expect(document.querySelector('.snack')).toBeNull();
    props.snack = { id: 2, text: 'next' };
    flushSync();
    vi.advanceTimersByTime(6000);
    expect(onexpire).toHaveBeenCalledTimes(1);
    unmount(app);
  });
});
