import { render } from 'svelte/server';
import { describe, expect, it } from 'vitest';
import ChoreRow from './ChoreRow.svelte';
import type { Chore } from '../lib/api';

const base: Chore = {
  id: 7,
  household_id: 1,
  name: 'Clean toilet',
  category: 'Bad',
  interval: '1w',
  archived: false,
  last: { done_on: '2026-11-13', by: { id: 2, display_name: 'Rafi', former: false } },
  days_since: 1,
  due_level: 'fresh',
  days_overdue: 0,
  usual_interval: null,
};

const noop = () => {};

describe('ChoreRow', () => {
  it('done_button_has_accessible_name_and_row_never_wraps', () => {
    const { body } = render(ChoreRow, { props: { chore: base, today: '2026-11-14', lang: 'en', ondone: noop, onlong: noop } });
    expect(body).toContain('aria-label="Mark Clean toilet done"');
    expect(body).toContain('Rafi · yesterday');
    expect(body).toContain('href="/chores/7"');
    expect(body.match(/data-nowrap/g)?.length).toBeGreaterThanOrEqual(3);
  });

  it('never_done_row', () => {
    const c = { ...base, last: null, days_since: null, due_level: 'never' as const };
    const { body } = render(ChoreRow, { props: { chore: c, today: '2026-11-14', lang: 'de', ondone: noop, onlong: noop } });
    expect(body).toContain('nie erledigt');
    expect(body).toMatch(/>nie</);
  });
});
