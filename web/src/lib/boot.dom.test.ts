// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest';
import { cachedSession, forgetSession, rememberSession } from './boot';
import { config, me } from '../screens/testkit';

afterEach(() => localStorage.clear());

describe('offline start', () => {
  it('last_session_kept_for_offline_start', () => {
    expect(cachedSession()).toBeNull();
    rememberSession(config, me);
    expect(cachedSession()).toEqual({ config, me });
    forgetSession();
    expect(cachedSession()).toBeNull();
    localStorage.setItem('wgc.session', '{broken');
    expect(cachedSession()).toBeNull();
  });
});
