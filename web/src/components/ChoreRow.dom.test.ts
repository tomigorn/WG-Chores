// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChoreRow from './ChoreRow.svelte';
import type { Chore } from '../lib/api';

const chore: Chore = {
  id: 7,
  name: 'Clean toilet',
  category: 'Bad',
  interval: '1w',
  archived: false,
  last: null,
  days_since: null,
  due_level: 'never',
  days_overdue: 0,
  usual_interval: null,
};

const pe = (type: string, x = 10, y = 10) => new PointerEvent(type, { clientX: x, clientY: y, button: 0, isPrimary: true, bubbles: true, cancelable: true });

function setup() {
  const ondone = vi.fn();
  const onlong = vi.fn();
  const target = document.createElement('ul');
  document.body.append(target);
  const app = mount(ChoreRow, { target, props: { chore, today: '2026-11-14', lang: 'en', ondone, onlong } });
  flushSync();
  return { ondone, onlong, app, link: target.querySelector('a')!, button: target.querySelector('button')! };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('ChoreRow interaction', () => {
  it('check_button_marks_done', () => {
    const { ondone, onlong, button, app } = setup();
    button.dispatchEvent(pe('pointerdown'));
    vi.advanceTimersByTime(100);
    button.dispatchEvent(pe('pointerup'));
    button.click();
    expect(ondone).toHaveBeenCalledTimes(1);
    expect(onlong).not.toHaveBeenCalled();
    unmount(app);
  });

  it('click_after_long_press_is_suppressed', () => {
    const { ondone, onlong, link, button, app } = setup();
    link.dispatchEvent(pe('pointerdown'));
    vi.advanceTimersByTime(500);
    expect(onlong).toHaveBeenCalledTimes(1);
    link.dispatchEvent(pe('pointerup'));
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    link.dispatchEvent(click);
    expect(click.defaultPrevented).toBe(true);

    button.dispatchEvent(pe('pointerdown'));
    vi.advanceTimersByTime(600);
    button.dispatchEvent(pe('pointerup'));
    button.click();
    expect(ondone).not.toHaveBeenCalled();
    expect(onlong).toHaveBeenCalledTimes(2);

    link.dispatchEvent(pe('pointerdown'));
    link.dispatchEvent(pe('pointermove', 10, 30));
    vi.advanceTimersByTime(600);
    expect(onlong).toHaveBeenCalledTimes(2);
    unmount(app);
  });
});
