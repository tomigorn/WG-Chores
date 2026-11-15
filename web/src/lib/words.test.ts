import { describe, expect, it } from 'vitest';
import { ApiError, OfflineError, type Chore } from './api';
import { canConfirmDelete, errorText, choreMeta, droppedMessage, droppedUndoMessage, expiresIn, initials, INTERVAL_ORDER, intervalWord, lastDone, memberDirectory, passwordProblem, usualText } from './words';

const chore = (p: Partial<Chore> = {}): Chore => ({
  id: 7,
  household_id: 1,
  name: 'Clean toilet',
  category: 'Bathroom',
  interval: '1w',
  archived: false,
  last: { done_on: '2026-10-19', by: { id: 2, display_name: 'Rafi', former: false } },
  days_since: 26,
  due_level: 'long_overdue',
  days_overdue: 19,
  usual_interval: 9,
  ...p,
});

describe('words', () => {
  it('interval_words_in_order', () => {
    expect(INTERVAL_ORDER.map((c) => intervalWord(c, 'en'))).toEqual(['every 3 days', 'every week', 'every 2 weeks', 'every month', 'every 3 months', 'every 6 months', 'every year']);
    expect(INTERVAL_ORDER.map((c) => intervalWord(c, 'de'))).toEqual(['alle 3 Tage', 'jede Woche', 'alle 2 Wochen', 'jeden Monat', 'alle 3 Monate', 'alle 6 Monate', 'jedes Jahr']);
    expect(intervalWord(null, 'en')).toBe('every month');
  });

  it('chore_meta_category_and_interval', () => {
    expect(choreMeta(chore(), 'en')).toBe('Bathroom · every week');
    expect(choreMeta(chore({ category: null, interval: null }), 'en')).toBe('every month');
    expect(choreMeta(chore({ interval: '2w' }), 'de')).toBe('Bathroom · alle 2 Wochen');
  });

  it('last_done_lines', () => {
    expect(lastDone(chore(), '2026-11-14', 'en')).toEqual({ when: 'Last done 19\u00a0Oct', by: 'by Rafi', overdue: '19 days overdue' });
    expect(lastDone(chore({ days_overdue: 0, last: { done_on: '2026-11-14', by: { id: 1, display_name: 'Anna', former: false } }, days_since: 0 }), '2026-11-14', 'en')).toEqual({ when: 'Last done today', by: 'by Anna', overdue: null });
    expect(lastDone(chore({ days_overdue: 1 }), '2026-11-14', 'de')).toEqual({ when: 'Zuletzt erledigt 19.\u00a0Okt.', by: 'von Rafi', overdue: '1 Tag überfällig' });
    expect(lastDone(chore({ last: null, days_since: null, due_level: 'never', days_overdue: 0 }), '2026-11-14', 'en')).toEqual({ when: 'Never done', by: null, overdue: null });
  });

  it('usual_interval_line', () => {
    expect(usualText(9, 'en')).toBe('Usually done every ~9 days');
    expect(usualText(1, 'en')).toBe('Usually done about daily');
    expect(usualText(9, 'de')).toBe('Meist alle ~9 Tage erledigt');
    expect(usualText(null, 'en')).toBeNull();
  });

  it('invite_expiry_words', () => {
    const now = new Date('2026-11-14T10:00:00Z');
    expect(expiresIn('2026-11-21T09:00:00.000Z', now, 'en')).toBe('expires in 6 days');
    expect(expiresIn('2026-11-15T11:00:00.000Z', now, 'en')).toBe('expires in 1 day');
    expect(expiresIn('2026-11-14T15:00:00.000Z', now, 'en')).toBe('expires in 5 hours');
    expect(expiresIn('2026-11-14T10:20:00.000Z', now, 'en')).toBe('expires in less than an hour');
    expect(expiresIn('2026-11-14T09:00:00.000Z', now, 'en')).toBe('expired');
    expect(expiresIn('2026-11-21T09:00:00.000Z', now, 'de')).toBe('läuft in 6 Tagen ab');
  });

  it('initials_of_names', () => {
    expect(initials('Jane Doe')).toBe('JD');
    expect(initials('  anna ')).toBe('A');
    expect(initials('Élise van der Berg')).toBe('ÉV');
    expect(initials('')).toBe('?');
  });

  it('dropped_marks_message_without_empty_name', () => {
    const names = new Map([[7, 'Clean toilet']]);
    expect(droppedMessage([{ choreId: 7 }], names, 'en')).toBe('Clean toilet could not be saved and was removed from the queue.');
    expect(droppedMessage([{ choreId: 99 }], names, 'en')).toBe('A done mark could not be saved and was removed from the queue.');
    expect(droppedMessage([{ choreId: 7 }, { choreId: 99 }], names, 'en')).toBe('2 done marks could not be saved and were removed from the queue.');
    expect(droppedMessage([], names, 'en')).toBeNull();
  });

  it('dropped_undo_message', () => {
    const names = new Map([[7, 'Clean toilet']]);
    expect(droppedUndoMessage([{ choreId: 7 }], names, 'en')).toBe("Couldn't undo Clean toilet; the mark stays.");
    expect(droppedUndoMessage([{ choreId: 99 }], names, 'en')).toBe("Couldn't undo a done mark; it stays.");
    expect(droppedUndoMessage([{ choreId: 7 }, { choreId: 99 }], names, 'en')).toBe("Couldn't undo 2 done marks; they stay.");
    expect(droppedUndoMessage([], names, 'en')).toBeNull();
    expect(droppedUndoMessage([{ choreId: 7 }], names, 'de')).toBe('Rückgängig für Clean toilet nicht möglich; die Markierung bleibt.');
  });

  it('delete_confirmation_matches_account', () => {
    expect(canConfirmDelete({ kind: 'local', username: 'anna', display_name: 'Anna' }, 'any password')).toBe(true);
    expect(canConfirmDelete({ kind: 'local', username: 'anna', display_name: 'Anna' }, ' ')).toBe(true);
    expect(canConfirmDelete({ kind: 'sso', username: 'anna', display_name: 'Jane Doe' }, 'Jane Doe ')).toBe(true);
    expect(canConfirmDelete({ kind: 'sso', username: 'anna', display_name: 'Jane Doe' }, 'jane doe')).toBe(false);
    expect(canConfirmDelete({ kind: 'local', username: null, display_name: 'Anna' }, '')).toBe(false);
  });

  it('password_rules', () => {
    expect(passwordProblem('short', 'short')).toBe('short');
    expect(passwordProblem('correct horse battery', 'correct horse batterz')).toBe('mismatch');
    expect(passwordProblem('correct horse battery', 'correct horse battery')).toBeNull();
  });

  it('member_directory_detected_from_404', async () => {
    expect(await memberDirectory(async () => Promise.reject(new ApiError(404, 'not_found', '')))).toBe(false);
    expect(await memberDirectory(async () => Promise.reject(new ApiError(400, 'query_too_short', '')))).toBe(true);
    expect(await memberDirectory(async () => [])).toBe(true);
    expect(await memberDirectory(async () => Promise.reject(new OfflineError()))).toBe(false);
    expect(await memberDirectory(async () => Promise.reject(new ApiError(500, 'internal', '')))).toBe(false);
    expect(await memberDirectory(async () => Promise.reject(new ApiError(403, 'forbidden', '')))).toBe(false);
  });

  it('error_text_for_loads_and_saves', () => {
    expect(errorText(new OfflineError(), 'en')).toBe('No connection. Check your internet and try again.');
    expect(errorText(new OfflineError(), 'en', 'done.connect')).toBe('Connect to the internet to save this');
    expect(errorText(new ApiError(403, 'wrong_password', ''), 'en')).toBe('The current password is wrong.');
    expect(errorText(new ApiError(500, 'weird_code', ''), 'en')).toBe('Something went wrong. Please try again.');
    expect(errorText(new Error('x'), 'en')).toBe('Something went wrong. Please try again.');
  });
});
