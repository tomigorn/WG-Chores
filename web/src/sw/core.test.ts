import { describe, expect, it } from 'vitest';
import { cacheName, notificationFromPush, safeUrl, staleCaches, swRoute, targetClient } from './core';

const ORIGIN = 'https://chores.example.com';
const PRECACHE = ['/', '/index.html', '/assets/index-abc.js', '/icons/icon-192.png', '/manifest.webmanifest'];
const req = (path: string, mode: RequestMode = 'cors', method = 'GET') => ({ url: ORIGIN + path, mode, method });

describe('service worker routing', () => {
  it('sw_route_decisions', () => {
    expect(swRoute(req('/households/3', 'navigate'), ORIGIN, PRECACHE)).toBe('navigate');
    expect(swRoute(req('/', 'navigate'), ORIGIN, PRECACHE)).toBe('navigate');
    expect(swRoute(req('/assets/index-abc.js'), ORIGIN, PRECACHE)).toBe('precached');
    expect(swRoute(req('/icons/icon-192.png'), ORIGIN, PRECACHE)).toBe('precached');
    expect(swRoute(req('/manifest.webmanifest'), ORIGIN, PRECACHE)).toBe('precached');
    expect(swRoute(req('/assets/other-xyz.js'), ORIGIN, PRECACHE)).toBe('network');
    expect(swRoute(req('/api/v1/households/3/chores'), ORIGIN, PRECACHE)).toBe('network');
    expect(swRoute(req('/api/v1/me', 'navigate'), ORIGIN, PRECACHE)).toBe('network');
    expect(swRoute(req('/auth/oidc/start', 'navigate'), ORIGIN, PRECACHE)).toBe('network');
    expect(swRoute(req('/auth/oidc/callback?code=x', 'navigate'), ORIGIN, PRECACHE)).toBe('network');
    expect(swRoute(req('/healthz'), ORIGIN, PRECACHE)).toBe('network');
    expect(swRoute(req('/sw.js'), ORIGIN, PRECACHE)).toBe('network');
    expect(swRoute(req('/assets/index-abc.js', 'cors', 'POST'), ORIGIN, PRECACHE)).toBe('network');
    expect(swRoute({ url: 'https://cdn.example.org/x.js', mode: 'cors', method: 'GET' }, ORIGIN, PRECACHE)).toBe('network');
    expect(swRoute({ url: 'https://other.example.org/', mode: 'navigate', method: 'GET' }, ORIGIN, PRECACHE)).toBe('network');
  });

  it('versioned_cache_and_cleanup', () => {
    expect(cacheName('abc123')).toBe('wgc-shell-abc123');
    expect(staleCaches(['wgc-shell-old', 'wgc-shell-abc123', 'someone-else'], 'abc123')).toEqual(['wgc-shell-old']);
  });
});

describe('service worker push', () => {
  it('push_payload_to_notification', () => {
    const n = notificationFromPush({ title: 'Rafi: done Clean toilet', body: 'Bathroom', url: '/households/3', tag: 'done-7' });
    expect(n.title).toBe('Rafi: done Clean toilet');
    expect(n.options).toEqual({ body: 'Bathroom', tag: 'done-7', data: { url: '/households/3' }, icon: '/icons/icon-192.png', badge: '/icons/badge-96.png' });
    expect(notificationFromPush(null).title).toBe('WG Chores');
    expect(notificationFromPush({ url: 'https://evil.example.org/x' }).options.data.url).toBe('/');
    expect(notificationFromPush({ url: '//evil.example.org/x' }).options.data.url).toBe('/');
  });

  it('safe_url_keeps_same_origin_paths', () => {
    expect(safeUrl('/households/3')).toBe('/households/3');
    expect(safeUrl(undefined)).toBe('/');
    expect(safeUrl('javascript:alert(1)')).toBe('/');
    expect(safeUrl('/\\evil.example.org')).toBe('/');
  });

  it('click_prefers_open_window', () => {
    const a = { url: ORIGIN + '/households/1', focused: false, type: 'window' };
    const b = { url: ORIGIN + '/settings', focused: true, type: 'window' };
    const foreign = { url: 'https://other.example.org/', focused: true, type: 'window' };
    expect(targetClient([a, b], ORIGIN)).toBe(b);
    expect(targetClient([a], ORIGIN)).toBe(a);
    expect(targetClient([foreign], ORIGIN)).toBeNull();
    expect(targetClient([], ORIGIN)).toBeNull();
  });
});
