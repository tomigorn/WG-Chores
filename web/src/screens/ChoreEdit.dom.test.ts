// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ChoreEdit from './ChoreEdit.svelte';
import { api, OfflineError } from '../lib/api';
import * as router from '../lib/router';

vi.mock('../lib/router', async (orig) => ({ ...(await orig<typeof import('../lib/router')>()), navigate: vi.fn() }));
import { clearSnack } from '../lib/snack';
import { button, config, dialogs, me, text, toilet, type } from './testkit';

let app: ReturnType<typeof mount> | null = null;

async function open(props: { choreId: number | null; householdId: number | null }) {
  app = mount(ChoreEdit, { target: document.body, props: { ...props, config, me, lang: 'en' } });
  await vi.waitFor(() => expect(document.querySelector('#chore-name')).not.toBeNull());
}

const chips = () => [...document.querySelectorAll<HTMLButtonElement>('.chips button')];
const save = () => document.querySelector<HTMLFormElement>('#chore-form')!.requestSubmit();

beforeEach(() => {
  dialogs();
  vi.spyOn(api, 'categories').mockResolvedValue([
    { name: 'Bathroom', chores: 3 },
    { name: 'Kitchen', chores: 1 },
  ]);
  vi.spyOn(api, 'chore').mockResolvedValue(toilet());
});

afterEach(() => {
  if (app) unmount(app);
  app = null;
  clearSnack();
  vi.restoreAllMocks();
  vi.mocked(router.navigate).mockReset();
  document.body.innerHTML = '';
});

describe('Add / edit chore', () => {
  it('edit_interval_none_means_month_hint', async () => {
    const create = vi.spyOn(api, 'createChore').mockImplementation(async (_h, c) => toilet({ id: 9, name: c.name }));
    await open({ choreId: null, householdId: 1 });
    expect(chips().map((c) => c.textContent!.trim())).toEqual(['every 3 days', 'every week', 'every 2 weeks', 'every month', 'every 2 months', 'every 3 months', 'every 6 months', 'every year']);
    expect(chips().every((c) => c.getAttribute('aria-pressed') === 'false')).toBe(true);
    expect(text()).toContain('Nothing picked = every month');
    chips()[1]!.click();
    flushSync();
    expect(chips()[1]!.getAttribute('aria-pressed')).toBe('true');
    chips()[1]!.click();
    flushSync();
    expect(chips()[1]!.getAttribute('aria-pressed')).toBe('false');
    type('#chore-name', '  Water plants ');
    save();
    await vi.waitFor(() => expect(create).toHaveBeenCalledWith(1, { name: 'Water plants', category: null, interval: null }));
    await vi.waitFor(() => expect(router.navigate).toHaveBeenCalledWith('/households/1', { replace: true }));
  });

  it('name_and_category_fields_have_no_autocorrect', async () => {
    await open({ choreId: null, householdId: 1 });
    for (const sel of ['#chore-name', '#chore-category']) {
      const el = document.querySelector(sel)!;
      expect([el.getAttribute('autocorrect'), el.getAttribute('autocapitalize'), el.getAttribute('spellcheck')]).toEqual(['off', 'off', 'false']);
    }
    expect([...document.querySelectorAll('datalist option')].map((o) => o.getAttribute('value'))).toEqual(['Bathroom', 'Kitchen']);
  });

  it('edit_sends_changed_interval_and_category', async () => {
    const patch = vi.spyOn(api, 'patchChore').mockResolvedValue(toilet());
    await open({ choreId: 7, householdId: null });
    expect(document.querySelector<HTMLInputElement>('#chore-name')!.value).toBe('Clean toilet');
    expect(chips()[1]!.getAttribute('aria-pressed')).toBe('true');
    chips()[2]!.click();
    type('#chore-category', 'Kitchen');
    expect(text()).not.toContain('for all');
    save();
    await vi.waitFor(() => expect(patch).toHaveBeenCalledWith(7, { name: 'Clean toilet', category: 'Kitchen', interval: '2w' }));
    await vi.waitFor(() => expect(router.navigate).toHaveBeenCalledWith('/chores/7', { replace: true }));
  });

  it('rename_category_from_edit', async () => {
    const rename = vi.spyOn(api, 'renameCategory').mockResolvedValue([]);
    const patch = vi.spyOn(api, 'patchChore').mockResolvedValue(toilet());
    await open({ choreId: 7, householdId: null });
    type('#chore-category', 'Bad');
    const box = document.querySelector<HTMLInputElement>('#rename-all')!;
    expect(box.closest('label')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('Rename “Bathroom” to “Bad” for all 3 chores');
    box.click();
    flushSync();
    save();
    await vi.waitFor(() => expect(patch).toHaveBeenCalled());
    expect(rename).toHaveBeenCalledWith(1, 'Bathroom', 'Bad');
    expect(patch).toHaveBeenCalledWith(7, { name: 'Clean toilet', interval: '1w', category: 'Bad' });
    expect(rename.mock.invocationCallOrder[0]!).toBeLessThan(patch.mock.invocationCallOrder[0]!);
  });

  it('archive_needs_confirm', async () => {
    const archive = vi.spyOn(api, 'archiveChore').mockResolvedValue(toilet({ archived: true }));
    await open({ choreId: 7, householdId: null });
    button('Archive chore')!.click();
    flushSync();
    expect(text()).toContain('Archive Clean toilet?');
    expect(archive).not.toHaveBeenCalled();
    document.querySelector<HTMLFormElement>('.confirm')!.requestSubmit();
    await vi.waitFor(() => expect(archive).toHaveBeenCalledWith(7));
    await vi.waitFor(() => expect(router.navigate).toHaveBeenCalledWith('/households/1', { replace: true }));
  });

  it('save_offline_says_connect', async () => {
    vi.spyOn(api, 'patchChore').mockRejectedValue(new OfflineError());
    await open({ choreId: 7, householdId: null });
    save();
    await vi.waitFor(() => expect(text()).toContain('Connect to the internet to save this'));
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('cancel_is_labelled_for_icon_only_layout', async () => {
    await open({ choreId: 7, householdId: null });
    const cancel = document.querySelector('header a')!;
    expect(cancel.getAttribute('aria-label')).toBe('Cancel');
    expect(cancel.getAttribute('href')).toBe('/chores/7');
    expect(cancel.querySelector('svg')).not.toBeNull();
  });

  it('retry_after_failed_patch_does_not_rename_again', async () => {
    const rename = vi.spyOn(api, 'renameCategory').mockResolvedValue([{ name: 'Bad', chores: 3 }, { name: 'Kitchen', chores: 1 }]);
    const patch = vi.spyOn(api, 'patchChore').mockRejectedValueOnce(new OfflineError()).mockResolvedValue(toilet({ category: 'Bad' }));
    await open({ choreId: 7, householdId: null });
    type('#chore-category', 'Bad');
    document.querySelector<HTMLInputElement>('#rename-all')!.click();
    flushSync();
    save();
    await vi.waitFor(() => expect(text()).toContain('Connect to the internet to save this'));
    expect(document.querySelector('#rename-all')).toBeNull();
    save();
    await vi.waitFor(() => expect(patch).toHaveBeenCalledTimes(2));
    expect(rename).toHaveBeenCalledTimes(1);
    expect(patch.mock.calls[1]![1]).toEqual({ name: 'Clean toilet', category: 'Bad', interval: '1w' });
  });

  it('load_offline_says_offline', async () => {
    vi.mocked(api.chore).mockRejectedValue(new OfflineError());
    app = mount(ChoreEdit, { target: document.body, props: { choreId: 7, householdId: null, config, me, lang: 'en' } });
    await vi.waitFor(() => expect(text()).toContain('No connection. Check your internet and try again.'));
  });
});
