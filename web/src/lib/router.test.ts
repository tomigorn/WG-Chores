import { describe, expect, it } from 'vitest';
import { matchRoute, safeNext } from './router';

describe('router', () => {
  it('matches_known_paths', () => {
    expect(matchRoute('/')).toEqual({ name: 'home' });
    expect(matchRoute('/login')).toEqual({ name: 'login' });
    expect(matchRoute('/households')).toEqual({ name: 'households' });
    expect(matchRoute('/households/12')).toEqual({ name: 'household', id: 12 });
    expect(matchRoute('/chores/7/')).toEqual({ name: 'chore', id: 7 });
    expect(matchRoute('/invite/abc_DEF-1')).toEqual({ name: 'invite', token: 'abc_DEF-1' }); // leak-check: ok
    expect(matchRoute('/settings')).toEqual({ name: 'settings' });
    expect(matchRoute('/account')).toEqual({ name: 'account' });
  });

  it('unknown_paths_are_null', () => {
    for (const p of ['/nope', '/households/x', '/chores/', '/invite/../x', '/households/1/2']) expect(matchRoute(p), p).toBeNull();
  });

  it('next_path_only_local', () => {
    expect(safeNext('/chores/3')).toBe('/chores/3');
    expect(safeNext('//evil.example.com')).toBe('/');
    expect(safeNext('https://evil.example.com')).toBe('/');
    expect(safeNext('/login')).toBe('/');
    expect(safeNext(null)).toBe('/');
  });
});
