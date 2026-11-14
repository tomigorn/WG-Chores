import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LongPress } from './longpress';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

describe('long press', () => {
  it('long_press_opens_sheet_short_tap_marks_done', () => {
    const onLong = vi.fn();
    const lp = new LongPress(onLong);
    lp.down(10, 10);
    vi.advanceTimersByTime(499);
    expect(onLong).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onLong).toHaveBeenCalledTimes(1);
    expect(lp.up()).toBe(true);

    lp.down(10, 10);
    vi.advanceTimersByTime(200);
    expect(lp.up()).toBe(false);
    vi.advanceTimersByTime(1000);
    expect(onLong).toHaveBeenCalledTimes(1);
  });

  it('movement_over_10px_or_scroll_cancels', () => {
    const onLong = vi.fn();
    const lp = new LongPress(onLong);
    lp.down(10, 10);
    lp.move(16, 18);
    vi.advanceTimersByTime(600);
    expect(onLong).toHaveBeenCalledTimes(1);

    lp.down(10, 10);
    lp.move(10, 21);
    vi.advanceTimersByTime(600);
    expect(onLong).toHaveBeenCalledTimes(1);
    expect(lp.up()).toBe(false);

    lp.down(10, 10);
    lp.cancel();
    vi.advanceTimersByTime(600);
    expect(onLong).toHaveBeenCalledTimes(1);
  });
});
