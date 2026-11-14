// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest';
import { applyTheme } from './theme';

const metas = () => [...document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')].map((m) => [m.getAttribute('media'), m.content]);

afterEach(() => (document.head.innerHTML = ''));

describe('theme colour metas', () => {
  it('theme_color_metas_follow_setting', () => {
    document.head.innerHTML = '<meta name="theme-color" media="(prefers-color-scheme: light)" content="#ffffff"><meta name="theme-color" media="(prefers-color-scheme: dark)" content="#111214">';
    applyTheme('dark');
    expect(metas()).toEqual([
      ['(prefers-color-scheme: light)', '#111214'],
      ['(prefers-color-scheme: dark)', '#111214'],
    ]);
    applyTheme('light');
    expect(metas().map(([, c]) => c)).toEqual(['#ffffff', '#ffffff']);
    applyTheme('system');
    expect(metas().map(([, c]) => c)).toEqual(['#ffffff', '#111214']);
  });
});
