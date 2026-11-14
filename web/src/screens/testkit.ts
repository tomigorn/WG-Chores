import { flushSync } from 'svelte';
import { vi } from 'vitest';
import type { Chore, Completion, Me, ServerConfig } from '../lib/api';

export const config: ServerConfig = { name: 'WG Chores', local_accounts: true, open_registration: false, oidc: null, notify_times: [], tz: 'Europe/Zurich', version: 'test' };
export const me: Me = { id: 1, username: 'anna', display_name: 'Anna', kind: 'local', picture_url: null, account_url: null, last_household_id: 1, notify_due: true, notify_done: true };
export const rafi = { id: 2, display_name: 'Rafi', former: false };
export const anna = { id: 1, display_name: 'Anna', former: false };

export const toilet = (p: Partial<Chore> = {}): Chore => ({
  id: 7,
  household_id: 1,
  name: 'Clean toilet',
  category: 'Bathroom',
  interval: '1w',
  archived: false,
  last: { done_on: '2026-10-19', by: rafi },
  days_since: 26,
  due_level: 'long_overdue',
  days_overdue: 19,
  usual_interval: 9,
  ...p,
});

export const entry = (id: string, done_on: string, by = rafi, note: string | null = null): Completion => ({ id, done_on, note, by, created_at: `${done_on}T10:00:00.000Z` });

export const button = (name: string) =>
  [...document.querySelectorAll<HTMLElement>('button, a')].find((b) => b.textContent?.trim() === name || b.getAttribute('aria-label') === name);

export const text = () => document.body.textContent?.replace(/\s+/g, ' ') ?? '';

export function type(sel: string, value: string) {
  const el = document.querySelector<HTMLInputElement>(sel)!;
  el.value = value;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  flushSync();
}

export function dialogs() {
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
}

export function fixedTime(iso = '2026-11-14T10:00:00Z') {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}
