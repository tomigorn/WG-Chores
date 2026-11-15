// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Chore from './Chore.svelte';
import SnackHost from '../components/SnackHost.svelte';
import { api, OfflineError } from '../lib/api';
import { clearSnack } from '../lib/snack';
import { startSync, stopSync } from '../lib/sync';
import { anna, button, config, dialogs, entry, fixedTime, me, text, toilet } from './testkit';

let apps: ReturnType<typeof mount>[] = [];

async function open() {
  apps.push(mount(SnackHost, { target: document.body }));
  apps.push(mount(Chore, { target: document.body, props: { id: 7, config, me, lang: 'en' } }));
  await vi.waitFor(() => expect(document.querySelector('.history li')).not.toBeNull());
}

beforeEach(() => {
  fixedTime();
  dialogs();
  vi.spyOn(api, 'chore').mockResolvedValue(toilet());
  vi.spyOn(api, 'history').mockResolvedValue({ items: [entry('a', '2026-10-19'), entry('b', '2026-10-12', anna, 'used the new cleaner')], next: null });
});

afterEach(async () => {
  await startSync(me.id).discard();
  for (const a of apps) unmount(a);
  apps = [];
  stopSync();
  clearSnack();
  vi.restoreAllMocks();
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('Chore page', () => {
  it('chore_page_shows_overdue_and_history', async () => {
    await open();
    const t = text();
    expect(document.querySelector('h1')!.textContent).toBe('Clean toilet');
    expect(t).toContain('Bathroom · every week');
    expect(document.querySelector('.days')!.textContent!.replace(/\s/g, '')).toBe('26d');
    expect(document.querySelector('.days')!.className).toContain('due-long_overdue');
    expect(t).toContain('Last done 19 Oct');
    expect(t).toContain('by Rafi');
    expect(t).toContain('19 days overdue');
    const glued = [...document.querySelectorAll('.by [data-nowrap]')].map((e) => e.textContent!.replace(/\u00a0/g, ' '));
    expect(glued).toEqual(['by Rafi', '· 19 days overdue']);
    expect(t).toContain('Usually done every ~9 days');
    const rows = [...document.querySelectorAll('.history li')].map((l) => l.textContent!.replace(/\s+/g, ' ').trim());
    expect(rows).toEqual(['19 Oct Rafi', '12 Oct Anna “used the new cleaner”']);
    expect(button('Edit Clean toilet')!.getAttribute('href')).toBe('/chores/7/edit');
    expect(button('Done today')).toBeDefined();
    expect(button('Other date…')).toBeDefined();
  });

  it('other_date_is_an_icon_button_next_to_done_today', async () => {
    await open();
    const foot = document.querySelector('.foot')!;
    const [doneToday, other] = [...foot.querySelectorAll('button')];
    expect(doneToday!.textContent!.trim()).toBe('Done today');
    expect(other!.getAttribute('aria-label')).toBe('Other date…');
    expect(other!.getAttribute('title')).toBe('Other date…');
    expect(other!.textContent!.trim()).toBe('');
    expect(other!.querySelector('svg')).not.toBeNull();
    other!.click();
    flushSync();
    expect(document.querySelector('dialog[open]')).not.toBeNull();
  });

  it('back_arrow_steps_back_to_the_list_it_came_from', async () => {
    history.replaceState({ key: 'k1', from: '/households/1' }, '', '/chores/7');
    const back = vi.spyOn(history, 'back').mockImplementation(() => {});
    await open();
    const link = document.querySelector<HTMLAnchorElement>('header a[aria-label="Back"]')!;
    expect(link.getAttribute('href')).toBe('/households/1');
    const ev = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });
    link.dispatchEvent(ev);
    expect(back).toHaveBeenCalled();
    expect(ev.defaultPrevented).toBe(true);
    history.replaceState(null, '', '/');
  });

  it('usual_and_overdue_hidden_when_not_applicable', async () => {
    vi.mocked(api.chore).mockResolvedValue(toilet({ usual_interval: null, days_overdue: 0, days_since: 3, due_level: 'soon', last: { done_on: '2026-11-11', by: anna } }));
    await open();
    expect(text()).not.toContain('Usually done');
    expect(text()).not.toContain('overdue');
  });

  it('done_today_marks_and_refreshes', async () => {
    const complete = vi.spyOn(api, 'complete').mockImplementation(async (_c, b) => entry(b.id, b.done_on!, anna));
    await open();
    vi.mocked(api.chore).mockResolvedValue(toilet({ last: { done_on: '2026-11-14', by: anna }, days_since: 0, due_level: 'fresh', days_overdue: 0 }));
    button('Done today')!.click();
    flushSync();
    expect(document.querySelector('.days')!.textContent!.replace(/\s/g, '')).toBe('0d');
    await vi.waitFor(() => expect(complete).toHaveBeenCalled());
    expect(complete.mock.calls[0]![0]).toBe(7);
    expect(complete.mock.calls[0]![1].done_on).toBe('2026-11-14');
    await vi.waitFor(() => expect(api.history).toHaveBeenCalledTimes(2));
    expect(document.querySelector('.snack')!.textContent).toContain('Clean toilet');
  });

  it('tap_entry_to_delete_with_confirm', async () => {
    const del = vi.spyOn(api, 'deleteCompletion').mockResolvedValue(undefined);
    await open();
    (document.querySelectorAll('.history li button')[1] as HTMLElement).click();
    flushSync();
    expect((document.querySelector('#done-note') as HTMLInputElement).value).toBe('used the new cleaner');
    button('Delete entry')!.click();
    flushSync();
    expect(text()).toContain('Delete this entry?');
    expect(del).not.toHaveBeenCalled();
    (document.querySelector('.confirm button[type=submit]') as HTMLElement).click();
    await vi.waitFor(() => expect(del).toHaveBeenCalledWith('b'));
  });

  it('entry_deleted_offline_is_hidden_and_sent_later', async () => {
    const del = vi.spyOn(api, 'deleteCompletion').mockRejectedValueOnce(new OfflineError()).mockResolvedValue(undefined);
    await open();
    (document.querySelectorAll('.history li button')[1] as HTMLElement).click();
    flushSync();
    button('Delete entry')!.click();
    flushSync();
    (document.querySelector('.confirm button[type=submit]') as HTMLElement).click();
    await vi.waitFor(() => expect(document.querySelectorAll('.history li')).toHaveLength(1));
    expect(document.querySelector('.snack')?.textContent).toContain('Entry deleted');
    expect(del).toHaveBeenCalledWith('b');
    await startSync(me.id).outbox.flush();
    expect(del).toHaveBeenCalledTimes(2);
    expect(await startSync(me.id).outbox.items()).toEqual([]);
  });

  it('entry_change_offline_says_connect', async () => {
    vi.spyOn(api, 'patchCompletion').mockRejectedValue(new OfflineError());
    await open();
    (document.querySelector('.history li button') as HTMLElement).click();
    flushSync();
    button('Yesterday')!.click();
    flushSync();
    document.querySelector<HTMLFormElement>('dialog form')!.requestSubmit();
    await vi.waitFor(() => expect(document.querySelector('.snack')?.textContent).toContain('Connect to the internet to save this'));
  });

  it('older_history_pages_load', async () => {
    vi.mocked(api.history).mockResolvedValueOnce({ items: [entry('a', '2026-10-19')], next: 'cur1' }).mockResolvedValueOnce({ items: [entry('c', '2026-09-24')], next: null });
    await open();
    button('Load older entries')!.click();
    await vi.waitFor(() => expect(document.querySelectorAll('.history li')).toHaveLength(2));
    expect(vi.mocked(api.history).mock.calls[1]![1]).toMatchObject({ before: 'cur1' });
    expect(button('Load older entries')).toBeUndefined();
  });

  it('edit_hidden_while_offline', async () => {
    await open();
    expect(button('Edit Clean toilet')).toBeDefined();
    vi.mocked(api.chore).mockRejectedValue(new OfflineError());
    dispatchEvent(new Event('online'));
    await vi.waitFor(() => expect(button('Edit Clean toilet')).toBeUndefined());
    expect(document.querySelectorAll('.history li')).toHaveLength(2);
  });
});
