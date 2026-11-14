import type { By, Chore } from './api';
import { daysOverdue, dueLevel, intervalDays } from './due';
import { formatDate, tIn, type Lang } from './i18n';

export interface ChoreGroup {
  category: string | null;
  chores: Chore[];
}

export interface PendingDone {
  uuid: string;
  choreId: number;
  doneOn: string;
  note: string | null;
}

export function groupChores(chores: readonly Chore[], locale: string): ChoreGroup[] {
  const coll = new Intl.Collator(locale, { sensitivity: 'base', numeric: true });
  const exact = new Intl.Collator(locale, { numeric: true });
  const byKey = new Map<string, ChoreGroup>();
  for (const c of chores) {
    const name = c.category?.trim() || null;
    const key = name === null ? '' : 'c' + name.toLocaleLowerCase(locale);
    const g = byKey.get(key);
    if (!g) byKey.set(key, { category: name, chores: [c] });
    else {
      g.chores.push(c);
      if (name !== null && g.category !== null && exact.compare(name, g.category) < 0) g.category = name;
    }
  }
  const groups = [...byKey.values()];
  for (const g of groups) g.chores.sort((a, b) => coll.compare(a.name, b.name) || a.id - b.id);
  return groups.sort((a, b) => {
    if (a.category === null) return 1;
    if (b.category === null) return -1;
    return coll.compare(a.category, b.category);
  });
}

const dayFormats = new Map<string, Intl.DateTimeFormat>();

export function todayIn(tz: string, now: Date = new Date()): string {
  let f = dayFormats.get(tz);
  if (!f) {
    try {
      f = new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' });
    } catch {
      f = new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit' });
    }
    dayFormats.set(tz, f);
  }
  const p = Object.fromEntries(f.formatToParts(now).map((x) => [x.type, x.value]));
  return `${p.year}-${p.month}-${p.day}`;
}

function dayNumber(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number);
  return Math.round(Date.UTC(y ?? 0, (m ?? 1) - 1, d ?? 1) / 86_400_000);
}

export function daysBetween(from: string, to: string): number {
  return dayNumber(to) - dayNumber(from);
}

export function addDays(iso: string, n: number): string {
  return new Date((dayNumber(iso) + n) * 86_400_000).toISOString().slice(0, 10);
}

export function dayWord(doneOn: string, today: string, lang: Lang): string {
  const d = daysBetween(doneOn, today);
  if (d === 0) return tIn(lang, 'day.today');
  if (d === 1) return tIn(lang, 'day.yesterday');
  const sameYear = doneOn.slice(0, 4) === today.slice(0, 4);
  return formatDate(doneOn, lang, sameYear ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' });
}

export function doneWhen(doneOn: string, today: string, lang: Lang): string {
  const w = dayWord(doneOn, today, lang);
  return daysBetween(doneOn, today) <= 1 ? w : tIn(lang, 'day.on', { date: w });
}

export function badgeText(c: Chore, lang: Lang): string {
  return c.days_since === null ? tIn(lang, 'badge.never') : tIn(lang, 'badge.days', { n: c.days_since });
}

function plural(lang: Lang, key: string, n: number): string {
  return tIn(lang, n === 1 ? `${key}_one` : `${key}_other`, { n });
}

export function badgeLabel(c: Chore, lang: Lang): string {
  if (c.days_since === null) return tIn(lang, 'badge.never_label');
  const since = plural(lang, 'badge.since', c.days_since);
  return c.days_overdue > 0 ? `${since}, ${plural(lang, 'badge.overdue', c.days_overdue)}` : since;
}

export function applyCompletion(c: Chore, done: { doneOn: string; by: By }, today: string): Chore {
  if (c.last && c.last.done_on > done.doneOn) return c;
  const days = Math.max(0, daysBetween(done.doneOn, today));
  const iv = intervalDays(c.interval);
  return { ...c, last: { done_on: done.doneOn, by: done.by }, days_since: days, due_level: dueLevel(days, iv), days_overdue: daysOverdue(days, iv) };
}

export function withPending(chores: readonly Chore[], pending: readonly PendingDone[], me: By, today: string): Chore[] {
  if (!pending.length) return chores as Chore[];
  return chores.map((c) => pending.filter((p) => p.choreId === c.id).reduce((acc, p) => applyCompletion(acc, { doneOn: p.doneOn, by: me }, today), c));
}

export function rebase(chores: readonly Chore[], today: string): Chore[] {
  return chores.map((c) => (c.last ? applyCompletion({ ...c, last: null }, { doneOn: c.last.done_on, by: c.last.by }, today) : c));
}
