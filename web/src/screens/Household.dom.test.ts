// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Household from './Household.svelte';
import { api, ApiError, OfflineError, type Chore, type Completion, type Me, type ServerConfig } from '../lib/api';
import { stopSync } from '../lib/sync';

const config: ServerConfig = { name: 'WG Chores', local_accounts: true, open_registration: false, oidc: null, notify_times: [], tz: 'Europe/Zurich', version: 'test' };
const me: Me = { id: 1, username: 'anna', display_name: 'Anna', kind: 'local', picture_url: null, account_url: null, last_household_id: 1, notify_due: true, notify_done: true };
const rafi = { id: 2, display_name: 'Rafi', former: false };

const toilet = (): Chore => ({
  id: 7,
  name: 'Clean toilet',
  category: 'Bad',
  interval: '1w',
  archived: false,
  last: { done_on: '2026-10-19', by: rafi },
  days_since: 26,
  due_level: 'long_overdue',
  days_overdue: 19,
  usual_interval: null,
});

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const completion = (id: string, done_on: string): Completion => ({ id, done_on, note: null, by: { id: 1, display_name: 'Anna', former: false }, created_at: '' });

let app: ReturnType<typeof mount> | null = null;

async function open() {
  app = mount(Household, {
    target: document.body,
    props: { hid: 1, config, me, lang: 'en', theme: 'system', ontheme: () => {}, onlogout: () => {}, onopened: () => {} },
  });
  await vi.waitFor(() => expect(document.querySelector('.row')).not.toBeNull());
}

const badge = () => document.querySelector('.row .badge')!.textContent;
const who = () => document.querySelector('.row .who')!.textContent;
const snack = () => document.querySelector('.snack')?.textContent?.trim() ?? '';
const button = (name: string) => [...document.querySelectorAll('button')].find((b) => b.textContent?.trim() === name || b.getAttribute('aria-label') === name)!;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-14T10:00:00Z'));
  vi.spyOn(api, 'household').mockResolvedValue({ id: 1, name: 'WG Zürich', member_count: 2 });
  vi.spyOn(api, 'chores').mockResolvedValue([toilet()]);
  vi.spyOn(api, 'setLastHousehold').mockResolvedValue(undefined);
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
});

afterEach(() => {
  if (app) unmount(app);
  app = null;
  stopSync();
  vi.restoreAllMocks();
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('Household screen', () => {
  it('optimistic_mark_then_revert_on_rejection', async () => {
    const sent = deferred<Completion>();
    vi.spyOn(api, 'complete').mockReturnValue(sent.promise);
    await open();
    button('Mark Clean toilet done').click();
    flushSync();
    expect(badge()).toBe('0 d');
    expect(who()).toBe('Anna · today');
    expect(snack()).toContain('Clean toilet');
    sent.reject(new ApiError(404, 'not_found', 'gone'));
    await vi.waitFor(() => expect(badge()).toBe('26 d'));
    expect(snack()).toContain('could not be saved');
  });

  it('stale_response_does_not_override_an_undo', async () => {
    const doneList = [{ ...toilet(), last: { done_on: '2026-11-14', by: { id: 1, display_name: 'Anna', former: false } }, days_since: 0, due_level: 'fresh' as const, days_overdue: 0 }];
    vi.spyOn(api, 'complete').mockImplementation(async (_c, b) => completion(b.id, b.done_on!));
    vi.spyOn(api, 'deleteCompletion').mockReturnValue(new Promise(() => {}));
    await open();
    vi.mocked(api.chores).mockResolvedValue(doneList);
    button('Mark Clean toilet done').click();
    await vi.waitFor(() => expect(api.chores).toHaveBeenCalledTimes(2));
    await new Promise((r) => setTimeout(r, 10));
    const late = deferred<Chore[]>();
    vi.mocked(api.chores).mockReturnValueOnce(late.promise);
    document.dispatchEvent(new Event('visibilitychange'));
    button('Undo').click();
    flushSync();
    expect(badge()).toBe('26 d');
    late.resolve(doneList);
    await new Promise((r) => setTimeout(r, 10));
    expect(badge()).toBe('26 d');
  });

  it('undo_restores_row_and_deletes', async () => {
    const complete = vi.spyOn(api, 'complete').mockImplementation(async (_c, b) => completion(b.id, b.done_on!));
    const del = vi.spyOn(api, 'deleteCompletion').mockResolvedValue(undefined);
    await open();
    button('Mark Clean toilet done').click();
    await vi.waitFor(() => expect(complete).toHaveBeenCalled());
    button('Undo').click();
    flushSync();
    expect(badge()).toBe('26 d');
    await vi.waitFor(() => expect(del).toHaveBeenCalledWith(complete.mock.calls[0]![1].id));
  });

  it('sheet_save_marks_with_picked_day_and_note', async () => {
    const complete = vi.spyOn(api, 'complete').mockImplementation(async (_c, b) => completion(b.id, b.done_on!));
    await open();
    document.querySelector('.row a')!.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true }));
    flushSync();
    button('Yesterday').click();
    const note = document.querySelector<HTMLInputElement>('#done-note')!;
    note.value = 'new cleaner';
    note.dispatchEvent(new Event('input', { bubbles: true }));
    flushSync();
    document.querySelector('form')!.requestSubmit();
    flushSync();
    expect(who()).toBe('Anna · yesterday');
    await vi.waitFor(() => expect(complete).toHaveBeenCalled());
    expect(complete.mock.calls[0]![1]).toMatchObject({ done_on: '2026-11-13', note: 'new cleaner' });
  });

  it('offline_edit_shows_connect_message', async () => {
    vi.spyOn(api, 'complete').mockImplementation(async (_c, b) => completion(b.id, b.done_on!));
    const patch = vi.spyOn(api, 'patchCompletion').mockRejectedValue(new OfflineError());
    await open();
    vi.mocked(api.chores).mockResolvedValue([{ ...toilet(), last: { done_on: '2026-11-14', by: { id: 1, display_name: 'Anna', former: false } }, days_since: 0, due_level: 'fresh', days_overdue: 0 }]);
    button('Mark Clean toilet done').click();
    await vi.waitFor(() => expect(api.complete).toHaveBeenCalled());
    await vi.waitFor(() => expect(button('Other date')).toBeDefined());
    button('Other date').click();
    flushSync();
    button('Yesterday').click();
    flushSync();
    document.querySelector('form')!.requestSubmit();
    await vi.waitFor(() => expect(snack()).toBe('Connect to the internet to save this'));
    expect(patch).toHaveBeenCalled();
    expect(badge()).toBe('0 d');
  });

  it('today_is_taken_at_tap_time', async () => {
    vi.setSystemTime(new Date('2026-11-14T22:59:00Z'));
    const complete = vi.spyOn(api, 'complete').mockImplementation(async (_c, b) => completion(b.id, b.done_on!));
    await open();
    vi.setSystemTime(new Date('2026-11-14T23:01:00Z'));
    button('Mark Clean toilet done').click();
    await vi.waitFor(() => expect(complete).toHaveBeenCalled());
    expect(complete.mock.calls[0]![1].done_on).toBe('2026-11-15');
  });
});
