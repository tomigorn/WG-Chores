export type IntervalCode = '3d' | '1w' | '2w' | '1m' | '3m' | '6m' | '1y';
export type DueLevel = 'fresh' | 'soon' | 'close' | 'due' | 'overdue' | 'long_overdue' | 'never';

export const INTERVALS: Record<IntervalCode, number> = {
  '3d': 3,
  '1w': 7,
  '2w': 14,
  '1m': 30,
  '3m': 91,
  '6m': 182,
  '1y': 365,
};

export const DEFAULT_INTERVAL: IntervalCode = '1m';

export function intervalDays(code: IntervalCode | null | undefined): number {
  return (code && INTERVALS[code]) || INTERVALS[DEFAULT_INTERVAL];
}

// Integer comparisons, the same as the server, so r = d / iv never suffers from float rounding.
export function dueLevel(daysSince: number | null, intervalDaysValue: number): DueLevel {
  if (daysSince === null) return 'never';
  const d = daysSince;
  const iv = intervalDaysValue > 0 ? intervalDaysValue : 30;
  if (d * 2 < iv) return 'fresh';
  if (d * 5 < iv * 4) return 'soon';
  if (d < iv) return 'close';
  if (d * 2 < iv * 3) return 'due';
  if (d < iv * 2) return 'overdue';
  return 'long_overdue';
}

export function daysOverdue(daysSince: number, intervalDaysValue: number): number {
  const iv = intervalDaysValue > 0 ? intervalDaysValue : 30;
  return Math.max(0, daysSince - iv);
}

export function dayNumber(isoDate: string): number {
  const [y, m, d] = isoDate.split('-').map(Number);
  return Math.round(Date.UTC(y ?? 0, (m ?? 1) - 1, d ?? 1) / 86_400_000);
}

export function usualInterval(doneDatesNewestFirst: readonly string[]): number | null {
  const dates = doneDatesNewestFirst.slice(0, 10).map(dayNumber);
  if (dates.length < 3) return null;
  const gaps: number[] = [];
  for (let i = 1; i < dates.length; i++) gaps.push(dates[i - 1]! - dates[i]!);
  gaps.sort((a, b) => a - b);
  const n = gaps.length;
  return n % 2 === 1 ? gaps[(n - 1) / 2]! : Math.floor((gaps[n / 2 - 1]! + gaps[n / 2]! + 1) / 2);
}
