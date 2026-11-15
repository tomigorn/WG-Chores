// @vitest-environment happy-dom
import { mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Households from './Households.svelte';
import { api } from '../lib/api';
import * as router from '../lib/router';
import { me, text, type } from './testkit';

vi.mock('../lib/router', async (orig) => ({ ...(await orig<typeof import('../lib/router')>()), navigate: vi.fn(), restoreScroll: vi.fn() }));

let app: ReturnType<typeof mount> | null = null;

async function open(search = '') {
  app = mount(Households, { target: document.body, props: { me, lang: 'en', url: new URL(`http://localhost/households${search}`) } });
  await vi.waitFor(() => expect(api.households).toHaveBeenCalled());
}

beforeEach(() => {
  vi.spyOn(api, 'households').mockResolvedValue([
    { id: 1, name: 'WG Zürich', member_count: 2 },
    { id: 2, name: 'Family', member_count: 1 },
  ]);
});

afterEach(() => {
  if (app) unmount(app);
  app = null;
  vi.restoreAllMocks();
  vi.mocked(router.navigate).mockReset();
  document.body.innerHTML = '';
});

describe('Households', () => {
  it('lists_households_with_members_and_settings', async () => {
    await open();
    await vi.waitFor(() => expect(text()).toContain('WG Zürich'));
    const rows = [...document.querySelectorAll('.hh-row')].map((r) => r.textContent!.replace(/\s+/g, ' ').trim());
    expect(rows).toEqual(['WG Zürich 2 members', 'Family 1 member']);
    expect(document.querySelector('a[href="/households/1"]')).not.toBeNull();
    expect(document.querySelector('a[href="/households/1/settings"]')!.getAttribute('aria-label')).toBe('Settings for WG Zürich');
    expect(document.querySelector('#hh-name')).toBeNull();
  });

  it('new_query_opens_form_and_creates', async () => {
    const create = vi.spyOn(api, 'createHousehold').mockResolvedValue({ id: 5, name: 'Studio', member_count: 1 });
    await open('?new=1');
    expect(document.querySelector('#hh-name')).not.toBeNull();
    type('#hh-name', ' Studio ');
    document.querySelector<HTMLFormElement>('.new form')!.requestSubmit();
    await vi.waitFor(() => expect(create).toHaveBeenCalledWith('Studio'));
    await vi.waitFor(() => expect(router.navigate).toHaveBeenCalledWith('/households/5', { replace: true }));
  });

  it('scroll_is_restored_once_after_the_list_rendered', async () => {
    const restore = vi.mocked(router.restoreScroll);
    restore.mockClear();
    let rowsAtRestore = -1;
    restore.mockImplementation(() => void (rowsAtRestore = document.querySelectorAll('.hh-row').length));
    await open();
    await vi.waitFor(() => expect(restore).toHaveBeenCalledTimes(1));
    expect(rowsAtRestore).toBe(2);
  });
});
