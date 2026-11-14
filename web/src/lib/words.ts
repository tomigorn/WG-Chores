import { ApiError, type AccountKind, type Chore } from './api';
import { dayWord } from './chores';
import type { IntervalCode } from './due';
import { tIn, type Lang } from './i18n';

export const INTERVAL_ORDER: readonly IntervalCode[] = ['3d', '1w', '2w', '1m', '3m', '6m', '1y'];

function plural(lang: Lang, key: string, n: number, extra: Record<string, string | number> = {}): string {
  return tIn(lang, n === 1 ? `${key}_one` : `${key}_other`, { n, ...extra });
}

export function intervalWord(code: IntervalCode | null, lang: Lang): string {
  return tIn(lang, `interval.${code ?? '1m'}`);
}

export function choreMeta(c: Chore, lang: Lang): string {
  const iv = intervalWord(c.interval, lang);
  return c.category ? `${c.category} · ${iv}` : iv;
}

export function lastDone(c: Chore, today: string, lang: Lang): { when: string; by: string | null; overdue: string | null } {
  const overdue = c.days_overdue > 0 ? plural(lang, 'badge.overdue', c.days_overdue) : null;
  if (!c.last) return { when: tIn(lang, 'chore.never'), by: null, overdue: null };
  const when = tIn(lang, 'chore.last_done', { when: dayWord(c.last.done_on, today, lang).replace(/ /g, '\u00a0') });
  return { when, by: tIn(lang, 'chore.by', { who: c.last.by.display_name }), overdue };
}

export function usualText(days: number | null, lang: Lang): string | null {
  return days === null ? null : plural(lang, 'chore.usual', days);
}

export function expiresIn(expiresAt: string, now: Date, lang: Lang): string {
  const ms = Date.parse(expiresAt) - now.getTime();
  if (!(ms > 0)) return tIn(lang, 'expiry.expired');
  const hours = Math.floor(ms / 3_600_000);
  if (hours < 1) return tIn(lang, 'expiry.soon');
  if (hours < 24) return plural(lang, 'expiry.hours', hours);
  return plural(lang, 'expiry.days', Math.floor(hours / 24));
}

export function initials(name: string): string {
  return (
    name
      .trim()
      .split(/\s+/)
      .slice(0, 2)
      .map((w) => [...w][0]?.toLocaleUpperCase() ?? '')
      .join('') || '?'
  );
}

export function droppedMessage(dropped: readonly { choreId: number }[], names: ReadonlyMap<number, string>, lang: Lang): string | null {
  if (dropped.length === 0) return null;
  if (dropped.length > 1) return tIn(lang, 'done.rejected_many', { n: dropped.length });
  const name = names.get(dropped[0]!.choreId);
  return name ? tIn(lang, 'done.rejected', { name }) : tIn(lang, 'done.rejected_unknown');
}

export function canConfirmDelete(me: { kind: AccountKind; username: string | null; display_name: string }, typed: string): boolean {
  const given = typed.trim();
  if (!given) return false;
  if (me.kind === 'sso') return given === me.display_name;
  return !!me.username && me.username.toLowerCase() === given.toLowerCase();
}

export function passwordProblem(pw: string, repeat: string): 'short' | 'mismatch' | null {
  if ([...pw].length < 12) return 'short';
  return pw === repeat ? null : 'mismatch';
}

/** The server answers 404 on /users when its member directory is off, 400 for a too-short query when on. */
export async function memberDirectory(search: (q: string) => Promise<unknown>): Promise<boolean> {
  try {
    await search('');
    return true;
  } catch (e) {
    return e instanceof ApiError && e.status !== 404;
  }
}
