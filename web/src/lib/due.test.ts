import { describe, expect, it } from 'vitest';
import dueVectors from '../../../docs/test-vectors/due-level.json';
import usualVectors from '../../../docs/test-vectors/usual-interval.json';
import { dueLevel, intervalDays, usualInterval, daysOverdue, type IntervalCode } from './due';

describe('due rules', () => {
  it('due_vectors_match_server', () => {
    expect(dueVectors.length).toBeGreaterThanOrEqual(20);
    for (const c of dueVectors) {
      const level = dueLevel(c.days_since, intervalDays(c.interval as IntervalCode | null));
      expect(level, JSON.stringify(c)).toBe(c.level);
    }
  });

  it('usual_interval_vectors_match_server', () => {
    expect(usualVectors.length).toBeGreaterThanOrEqual(5);
    for (const c of usualVectors) {
      expect(usualInterval(c.dates), JSON.stringify(c)).toBe(c.usual);
    }
  });

  it('interval_days_table', () => {
    expect([null, '3d', '1w', '2w', '1m', '2m', '3m', '6m', '1y'].map((c) => intervalDays(c as IntervalCode | null))).toEqual([
      30, 3, 7, 14, 30, 61, 91, 182, 365,
    ]);
  });

  it('days_overdue_never_negative', () => {
    expect(daysOverdue(3, 7)).toBe(0);
    expect(daysOverdue(10, 7)).toBe(3);
  });
});
