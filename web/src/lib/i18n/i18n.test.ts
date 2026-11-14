import { describe, expect, it } from 'vitest';
import en from './en.json';
import de from './de.json';
import { pickLanguage, translate, formatDate } from './index';

function keys(o: object, prefix = ''): string[] {
  return Object.entries(o).flatMap(([k, v]) =>
    typeof v === 'object' && v !== null ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`],
  );
}

function params(s: string): string[] {
  return [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]!).sort();
}

describe('i18n', () => {
  it('i18n_keys_equal_in_en_and_de', () => {
    const k = keys(en).sort();
    expect(k.length).toBeGreaterThan(10);
    expect(keys(de).sort()).toEqual(k);
  });

  it('i18n_params_equal_in_en_and_de', () => {
    const lookup = (o: object, key: string) => key.split('.').reduce<unknown>((a, p) => (a as Record<string, unknown>)[p], o) as string;
    for (const key of keys(en)) expect(params(lookup(de, key)), key).toEqual(params(lookup(en, key)));
  });

  it('german_uses_swiss_ss', () => {
    expect(JSON.stringify(de)).not.toContain('ß');
  });

  it('translate_fills_params_and_falls_back_to_key', () => {
    expect(translate({ a: { b: 'Hi {name}' } }, 'a.b', { name: 'Anna' })).toBe('Hi Anna');
    expect(translate({}, 'x.y')).toBe('x.y');
  });

  it('language_from_navigator', () => {
    expect(pickLanguage(null, 'de-CH')).toBe('de');
    expect(pickLanguage(null, 'de')).toBe('de');
    expect(pickLanguage(null, 'en-GB')).toBe('en');
    expect(pickLanguage(null, 'fr-CH')).toBe('en');
    expect(pickLanguage(null, undefined)).toBe('en');
  });

  it('language_setting_wins_over_navigator', () => {
    expect(pickLanguage('en', 'de-CH')).toBe('en');
    expect(pickLanguage('de', 'en-US')).toBe('de');
    expect(pickLanguage('system', 'de-AT')).toBe('de');
    expect(pickLanguage('xx', 'en-US')).toBe('en');
  });

  it('dates_formatted_in_language', () => {
    expect(formatDate('2026-03-01', 'de')).toBe('1. März 2026');
    expect(formatDate('2026-03-01', 'en')).toBe('1 March 2026');
  });
});
