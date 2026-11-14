import { describe, expect, it } from 'vitest';
import type { By, Chore } from './api';
import { addDays, applyCompletion, rebase, badgeLabel, badgeText, dayWord, doneWhen, groupChores, todayIn, withPending } from './chores';

const anna: By = { id: 1, display_name: 'Anna', former: false };
const rafi: By = { id: 2, display_name: 'Rafi', former: false };

function chore(id: number, name: string, category: string | null, extra: Partial<Chore> = {}): Chore {
  return {
    id,
    name,
    category,
    interval: '1w',
    archived: false,
    last: null,
    days_since: null,
    due_level: 'never',
    days_overdue: 0,
    usual_interval: null,
    ...extra,
  };
}

describe('chore list logic', () => {
  it('groups_and_sorts_locale_aware', () => {
    const list = [
      chore(1, 'wipe stove', 'Küche'),
      chore(2, 'Älpli watering', 'balkon'),
      chore(3, 'Zebra plant', 'Balkon'),
      chore(4, 'abwaschen', 'Küche'),
      chore(5, 'Clean toilet', 'Bad'),
      chore(6, 'Ölen', 'Ärger'),
      chore(7, 'loose', null),
    ];
    const g = groupChores(list, 'de');
    expect(g.map((x) => x.category)).toEqual(['Ärger', 'Bad', 'balkon', 'Küche', null]);
    expect(g[2]!.chores.map((c) => c.name)).toEqual(['Älpli watering', 'Zebra plant']);
    expect(g[3]!.chores.map((c) => c.name)).toEqual(['abwaschen', 'wipe stove']);
    expect(g[4]!.chores.map((c) => c.id)).toEqual([7]);
  });

  it('category_casing_is_stable', () => {
    const a = [chore(1, 'x', 'Balkon'), chore(2, 'y', 'balkon'), chore(3, 'z', 'BALKON')];
    const names = [a, [...a].reverse(), [a[1]!, a[2]!, a[0]!]].map((l) => groupChores(l, 'de')[0]!.category);
    expect(new Set(names).size).toBe(1);
    expect(groupChores(a, 'de')[0]!.chores.length).toBe(3);
  });

  it('today_in_server_time_zone', () => {
    const t = new Date('2026-11-14T23:30:00Z');
    expect(todayIn('Europe/Zurich', t)).toBe('2026-11-15');
    expect(todayIn('America/New_York', t)).toBe('2026-11-14');
    expect(todayIn('No/Such_Zone', new Date('2026-11-14T12:00:00Z'))).toMatch(/^2026-11-1[45]$/);
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
  });

  it('relative_date_words', () => {
    expect(dayWord('2026-11-14', '2026-11-14', 'en')).toBe('today');
    expect(dayWord('2026-11-13', '2026-11-14', 'en')).toBe('yesterday');
    expect(dayWord('2026-11-03', '2026-11-14', 'en')).toBe('3 Nov');
    expect(dayWord('2025-12-30', '2026-11-14', 'en')).toBe('30 Dec 2025');
    expect(dayWord('2026-11-14', '2026-11-14', 'de')).toBe('heute');
    expect(dayWord('2026-11-13', '2026-11-14', 'de')).toBe('gestern');
    expect(dayWord('2026-11-03', '2026-11-14', 'de')).toBe('3. Nov.');
    expect(doneWhen('2026-11-14', '2026-11-14', 'en')).toBe('today');
    expect(doneWhen('2026-11-03', '2026-11-14', 'en')).toBe('on 3 Nov');
    expect(doneWhen('2026-11-03', '2026-11-14', 'de')).toBe('am 3. Nov.');
  });

  it('badge_label_accessible', () => {
    const c = chore(1, 'Clean toilet', 'Bad', { days_since: 26, due_level: 'long_overdue', days_overdue: 19, last: { done_on: '2026-10-19', by: anna } });
    expect(badgeText(c, 'en')).toBe('26 d');
    expect(badgeLabel(c, 'en')).toBe('26 days since, 19 days overdue');
    expect(badgeText(c, 'de')).toBe('26 T');
    expect(badgeLabel(c, 'de')).toBe('vor 26 Tagen, 19 Tage überfällig');
    const fresh = { ...c, days_since: 1, due_level: 'fresh' as const, days_overdue: 0 };
    expect(badgeLabel(fresh, 'en')).toBe('1 day since');
    expect(badgeLabel(fresh, 'de')).toBe('vor 1 Tag');
    const due = { ...c, days_since: 8, due_level: 'due' as const, days_overdue: 1 };
    expect(badgeLabel(due, 'en')).toBe('8 days since, 1 day overdue');
    const never = chore(2, 'Clean fridge', 'Küche');
    expect(badgeText(never, 'en')).toBe('never');
    expect(badgeText(never, 'de')).toBe('nie');
    expect(badgeLabel(never, 'en')).toBe('never done');
  });

  it('completion_updates_row_only_when_newer', () => {
    const c = chore(1, 'Clean toilet', 'Bad', { days_since: 26, due_level: 'long_overdue', days_overdue: 19, last: { done_on: '2026-10-19', by: rafi } });
    const done = applyCompletion(c, { doneOn: '2026-11-14', by: anna }, '2026-11-14');
    expect(done).toMatchObject({ last: { done_on: '2026-11-14', by: anna }, days_since: 0, due_level: 'fresh', days_overdue: 0 });
    const older = applyCompletion(c, { doneOn: '2026-10-01', by: anna }, '2026-11-14');
    expect(older).toEqual(c);
    const yesterday = applyCompletion(chore(2, 'x', null, { interval: '3d' }), { doneOn: '2026-11-11', by: anna }, '2026-11-14');
    expect(yesterday).toMatchObject({ days_since: 3, due_level: 'due', days_overdue: 0 });
  });

  it('pending_outbox_items_shown_on_cached_list', () => {
    const list = [chore(1, 'a', null), chore(2, 'b', null)];
    const out = withPending(list, [{ uuid: 'u', choreId: 2, doneOn: '2026-11-13', note: null }], anna, '2026-11-14');
    expect(out[0]).toBe(list[0]);
    expect(out[1]).toMatchObject({ days_since: 1, last: { by: anna } });
  });
});

describe('cached list', () => {
  it('cached_list_days_follow_today', () => {
    const c = chore(1, 'a', null, { interval: '1w', days_since: 2, due_level: 'fresh', last: { done_on: '2026-11-10', by: anna } });
    const [r] = rebase([c, chore(2, 'never', null)], '2026-11-19');
    expect(r).toMatchObject({ days_since: 9, due_level: 'due', days_overdue: 2 });
    expect(rebase([chore(2, 'never', null)], '2026-11-19')[0]!.due_level).toBe('never');
  });
});
