import { render } from 'svelte/server';
import { describe, expect, it } from 'vitest';
import DueBadge from './DueBadge.svelte';
import type { Chore } from '../lib/api';

const c: Chore = {
  id: 1,
  name: 'Clean toilet',
  category: 'Bad',
  interval: '1w',
  archived: false,
  last: { done_on: '2026-10-19', by: { id: 1, display_name: 'Anna', former: false } },
  days_since: 26,
  due_level: 'long_overdue',
  days_overdue: 19,
  usual_interval: null,
};

describe('DueBadge', () => {
  it('badge_label_accessible', () => {
    const { body } = render(DueBadge, { props: { chore: c, lang: 'en' } });
    expect(body).toContain('aria-label="26 days since, 19 days overdue"');
    expect(body).toContain('due-long_overdue');
    expect(body).toContain('data-nowrap');
    expect(body).toMatch(/>26 d</);
  });
});
