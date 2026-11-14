export type ThemeSetting = 'system' | 'light' | 'dark';

const KEY = 'wgc.theme';

export function parseTheme(v: string | null): ThemeSetting {
  return v === 'light' || v === 'dark' ? v : 'system';
}

export function themeSetting(): ThemeSetting {
  try {
    return parseTheme(localStorage.getItem(KEY));
  } catch {
    return 'system';
  }
}

export function applyTheme(s: ThemeSetting = themeSetting()): void {
  const root = document.documentElement;
  if (s === 'system') delete root.dataset.theme;
  else root.dataset.theme = s;
  const dark = s === 'dark' || (s === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#111214' : '#ffffff');
}

export function setTheme(s: ThemeSetting): void {
  try {
    if (s === 'system') localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, s);
  } catch {
    /* storage unavailable */
  }
  applyTheme(s);
}

export function watchSystemTheme(): void {
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => applyTheme());
}
