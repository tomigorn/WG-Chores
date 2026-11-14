// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import HouseholdSettings from './HouseholdSettings.svelte';
import { api, ApiError, type Member } from '../lib/api';
import * as router from '../lib/router';
import { clearSnack } from '../lib/snack';
import { button, config, dialogs, fixedTime, me, text, toilet, type } from './testkit';

vi.mock('../lib/router', async (orig) => ({ ...(await orig<typeof import('../lib/router')>()), navigate: vi.fn() }));

const annaM: Member = { id: 1, display_name: 'Anna', username: 'anna', picture_url: null, joined_at: '2026-01-01T00:00:00.000Z' };
const rafiM: Member = { id: 2, display_name: 'Rafi', username: 'rafi', picture_url: null, joined_at: '2026-01-02T00:00:00.000Z' };

let app: ReturnType<typeof mount> | null = null;

async function open(name = 'WG Chores') {
  app = mount(HouseholdSettings, { target: document.body, props: { hid: 1, config: { ...config, name }, me, lang: 'en' } });
  await vi.waitFor(() => expect(document.querySelector('.members li')).not.toBeNull());
  flushSync();
}

beforeEach(() => {
  fixedTime();
  dialogs();
  vi.spyOn(api, 'household').mockResolvedValue({ id: 1, name: 'WG Zürich', member_count: 2 });
  vi.spyOn(api, 'members').mockResolvedValue([annaM, rafiM]);
  vi.spyOn(api, 'invites').mockResolvedValue([{ id: 3, created_by: { id: 2, display_name: 'Rafi' }, created_at: '2026-11-13T10:00:00.000Z', expires_at: '2026-11-20T09:00:00.000Z' }]);
  vi.spyOn(api, 'chores').mockResolvedValue([toilet({ archived: true, name: 'Old chore', id: 9 })]);
  vi.spyOn(api, 'searchUsers').mockRejectedValue(new ApiError(404, 'not_found', ''));
});

afterEach(() => {
  if (app) unmount(app);
  app = null;
  clearSnack();
  vi.restoreAllMocks();
  vi.mocked(router.navigate).mockReset();
  document.body.innerHTML = '';
});

const confirm = () => document.querySelector<HTMLFormElement>('dialog[open] .confirm')!.requestSubmit();

describe('Household settings', () => {
  it('shows_members_invites_and_archived', async () => {
    await open();
    const members = [...document.querySelectorAll('.members li')].map((l) => l.textContent!.replace(/\s+/g, ' ').trim());
    expect(members).toEqual(['A Anna (you) @anna', 'R Rafi @rafi Remove']);
    expect(document.querySelector('.invite')!.textContent!.replace(/\s+/g, ' ').trim()).toBe('by Rafi expires in 5 days Cancel');
    expect(text()).toContain('Old chore');
    expect(api.chores).toHaveBeenCalledWith(1, { archived: true });
    expect(document.querySelector<HTMLInputElement>('#hh-name')!.value).toBe('WG Zürich');
  });

  it('leave_last_member_warns', async () => {
    vi.mocked(api.members).mockResolvedValue([annaM]);
    const leave = vi.spyOn(api, 'leaveHousehold').mockResolvedValue(undefined);
    await open();
    button('Leave household')!.click();
    flushSync();
    expect(text()).toContain('Delete WG Zürich?');
    expect(text()).toContain('You are the last member. Leaving deletes the household and all its chores.');
    expect(button('Delete household')).toBeDefined();
    confirm();
    await vi.waitFor(() => expect(leave).toHaveBeenCalledWith(1, true));
    await vi.waitFor(() => expect(router.navigate).toHaveBeenCalledWith('/households', { replace: true }));
  });

  it('leave_with_others_does_not_delete', async () => {
    const leave = vi.spyOn(api, 'leaveHousehold').mockResolvedValue(undefined);
    await open();
    button('Leave household')!.click();
    flushSync();
    expect(text()).toContain('Leave WG Zürich?');
    confirm();
    await vi.waitFor(() => expect(leave).toHaveBeenCalledWith(1, false));
  });

  it('add_member_by_exact_username_without_directory', async () => {
    const add = vi.spyOn(api, 'addMember').mockResolvedValue({ id: 4, display_name: 'Jane Doe', username: 'jane', picture_url: null, joined_at: '' });
    await open();
    expect(document.querySelector('#add-member')!.getAttribute('placeholder')).toBe('Their exact username');
    type('#add-member', ' jane ');
    document.querySelector<HTMLFormElement>('.add form')!.requestSubmit();
    await vi.waitFor(() => expect(add).toHaveBeenCalledWith(1, { username: 'jane' }));
  });

  it('add_member_from_directory_search', async () => {
    vi.useRealTimers();
    vi.mocked(api.searchUsers).mockImplementation(async (q) => {
      if (q.length < 2) throw new ApiError(400, 'query_too_short', '');
      return [{ id: 4, display_name: 'Jane Doe', username: 'jane', picture_url: null }];
    });
    const add = vi.spyOn(api, 'addMember').mockResolvedValue({ id: 4, display_name: 'Jane Doe', username: 'jane', picture_url: null, joined_at: '' });
    await open();
    await vi.waitFor(() => expect(document.querySelector('#add-member')!.getAttribute('placeholder')).toBe('Search by name or username'));
    type('#add-member', 'ja');
    await vi.waitFor(() => expect(button('Add Jane Doe')).toBeDefined());
    button('Add Jane Doe')!.click();
    await vi.waitFor(() => expect(add).toHaveBeenCalledWith(1, { user_id: 4 }));
  });

  it('remove_member_needs_confirm', async () => {
    const remove = vi.spyOn(api, 'removeMember').mockResolvedValue(undefined);
    await open();
    button('Remove Rafi')!.click();
    flushSync();
    expect(text()).toContain('Remove Rafi?');
    expect(remove).not.toHaveBeenCalled();
    confirm();
    await vi.waitFor(() => expect(remove).toHaveBeenCalledWith(1, 2));
  });

  it('create_invite_shows_link_with_copy_and_share', async () => {
    vi.spyOn(api, 'createInvite').mockResolvedValue({ id: 8, url: 'https://chores.example.com/invite/abc', expires_at: '2026-11-21T10:00:00.000Z' });
    const write = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: write }, configurable: true });
    const share = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });
    await open('Putzplan');
    button('Create invite link')!.click();
    await vi.waitFor(() => expect(document.querySelector<HTMLInputElement>('.new-link input')?.value).toBe('https://chores.example.com/invite/abc'));
    button('Copy')!.click();
    await vi.waitFor(() => expect(write).toHaveBeenCalledWith('https://chores.example.com/invite/abc'));
    button('Share')!.click();
    await vi.waitFor(() => expect(share).toHaveBeenCalledWith({ title: 'Join WG Zürich on Putzplan', url: 'https://chores.example.com/invite/abc' }));
    delete (navigator as unknown as Record<string, unknown>).share;
  });

  it('cancel_invite_and_restore_archived', async () => {
    const cancel = vi.spyOn(api, 'cancelInvite').mockResolvedValue(undefined);
    const restore = vi.spyOn(api, 'restoreChore').mockResolvedValue(toilet({ id: 9, name: 'Old chore' }));
    await open();
    button('Cancel invite by Rafi')!.click();
    await vi.waitFor(() => expect(cancel).toHaveBeenCalledWith(1, 3));
    button('Restore: Old chore')!.click();
    await vi.waitFor(() => expect(restore).toHaveBeenCalledWith(9));
  });

  it('rename_household', async () => {
    const rename = vi.spyOn(api, 'renameHousehold').mockResolvedValue({ id: 1, name: 'WG Bern', member_count: 2 });
    await open();
    type('#hh-name', 'WG Bern');
    document.querySelector<HTMLFormElement>('.rename')!.requestSubmit();
    await vi.waitFor(() => expect(rename).toHaveBeenCalledWith(1, 'WG Bern'));
  });
});
