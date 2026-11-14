import { describe, expect, it } from 'vitest';
import { parseTheme } from './theme';

describe('theme', () => {
  it('theme_setting_parsed_with_system_default', () => {
    expect(parseTheme('light')).toBe('light');
    expect(parseTheme('dark')).toBe('dark');
    expect(parseTheme('system')).toBe('system');
    expect(parseTheme(null)).toBe('system');
    expect(parseTheme('neon')).toBe('system');
  });
});
