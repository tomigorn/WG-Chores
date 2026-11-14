import en from './en.json';
import de from './de.json';

export type Lang = 'en' | 'de';
export type LangSetting = Lang | 'system';
type Dict = { [k: string]: string | Dict };

const dicts: Record<Lang, Dict> = { en, de };
const KEY = 'wgc.lang';

export function pickLanguage(setting: string | null, navigatorLang: string | undefined): Lang {
  if (setting === 'en' || setting === 'de') return setting;
  return navigatorLang?.toLowerCase().startsWith('de') ? 'de' : 'en';
}

export function translate(dict: Dict, key: string, params?: Record<string, string | number>): string {
  let v: string | Dict | undefined = dict;
  for (const p of key.split('.')) v = typeof v === 'object' ? v[p] : undefined;
  if (typeof v !== 'string') return key;
  return params ? v.replace(/\{(\w+)\}/g, (m, k: string) => (k in params ? String(params[k]) : m)) : v;
}

function storedSetting(): LangSetting {
  try {
    const s = localStorage.getItem(KEY);
    return s === 'en' || s === 'de' ? s : 'system';
  } catch {
    return 'system';
  }
}

let lang: Lang = 'en';
const listeners = new Set<(l: Lang) => void>();

export function initLanguage(): Lang {
  lang = pickLanguage(storedSetting(), typeof navigator === 'undefined' ? undefined : navigator.language);
  if (typeof document !== 'undefined') document.documentElement.lang = lang;
  return lang;
}

export function languageSetting(): LangSetting {
  return storedSetting();
}

export function setLanguage(s: LangSetting): void {
  try {
    if (s === 'system') localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, s);
  } catch {
    /* storage unavailable */
  }
  initLanguage();
  for (const l of listeners) l(lang);
}

export function onLanguage(l: (lang: Lang) => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function currentLanguage(): Lang {
  return lang;
}

export function t(key: string, params?: Record<string, string | number>): string {
  return translate(dicts[lang], key, params);
}

const formatters = new Map<string, Intl.DateTimeFormat>();

export function formatDate(isoDate: string, l: Lang = lang, opts: Intl.DateTimeFormatOptions = { day: 'numeric', month: 'long', year: 'numeric' }): string {
  const k = l + JSON.stringify(opts);
  let f = formatters.get(k);
  if (!f) {
    f = new Intl.DateTimeFormat(l === 'de' ? 'de-CH' : 'en-GB', { ...opts, timeZone: 'UTC' });
    formatters.set(k, f);
  }
  const [y, m, d] = isoDate.split('-').map(Number);
  return f.format(new Date(Date.UTC(y ?? 0, (m ?? 1) - 1, d ?? 1)));
}
