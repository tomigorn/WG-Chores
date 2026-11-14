import { render } from 'svelte/server';
import { describe, expect, it } from 'vitest';
import TopBar from './TopBar.svelte';
import type { Me } from '../lib/api';

const me: Me = { id: 1, username: 'anna', display_name: 'Jane Doe', kind: 'local', picture_url: null, account_url: null, last_household_id: 1, notify_due: true, notify_done: true };
const noop = () => {};
const props = { title: 'WG Zürich', me, pending: 0, offline: false, theme: 'system' as const, onswitch: noop, onstatus: noop, ontheme: noop, onnavigate: noop, onlogout: noop };

describe('TopBar', () => {
  it('pending_marker_and_initials', () => {
    expect(render(TopBar, { props }).body).not.toContain('waiting to sync');
    const { body } = render(TopBar, { props: { ...props, pending: 3 } });
    expect(body).toContain('aria-label="3 done marks waiting to sync"');
    expect(body).toMatch(/>JD</);
    expect(body).toContain('WG Zürich');
  });
});
