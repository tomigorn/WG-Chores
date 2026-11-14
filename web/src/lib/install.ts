export type BannerMode = 'android' | 'ios-safari' | 'ios-other' | 'desktop' | 'none';

export const DISMISS_DAYS = 30;
const DAY = 86_400_000;
const KEY = 'wgc.install-dismissed';

// set once the Play Store listing exists; the Android banner then also links there
export const PLAY_STORE_URL: string | null = null;

export function isIos(ua: string): boolean {
  return /iPhone|iPad|iPod/.test(ua);
}

export function installBannerMode(ua: string, standalone: boolean, dismissedAt: number | null, now: number): BannerMode {
  if (standalone) return 'none';
  if (dismissedAt !== null && dismissedAt <= now && now - dismissedAt < DISMISS_DAYS * DAY) return 'none';
  if (isIos(ua)) {
    const safari = /Version\/[\d.]+.*Safari\//.test(ua) && !/CriOS|FxiOS|EdgiOS|OPiOS|GSA\/|FBAN|FBAV|Instagram|Line\//.test(ua);
    return safari ? 'ios-safari' : 'ios-other';
  }
  if (/Android/.test(ua)) return 'android';
  return 'desktop';
}

export function isStandalone(): boolean {
  if (typeof window === 'undefined') return false;
  const nav = navigator as Navigator & { standalone?: boolean };
  return nav.standalone === true || (typeof matchMedia === 'function' && matchMedia('(display-mode: standalone)').matches);
}

export function dismissedAt(): number | null {
  try {
    const v = Number(localStorage.getItem(KEY));
    return Number.isFinite(v) && v > 0 ? v : null;
  } catch {
    return null;
  }
}

export function dismissBanner(now = Date.now()): void {
  try {
    localStorage.setItem(KEY, String(now));
  } catch {
    /* storage unavailable */
  }
}

interface InstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

let deferred: InstallPromptEvent | null = null;
const listeners = new Set<() => void>();

let capturing = false;

export function captureInstallPrompt(): void {
  if (capturing) return;
  capturing = true;
  addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferred = e as InstallPromptEvent;
    for (const l of listeners) l();
  });
  addEventListener('appinstalled', () => {
    deferred = null;
    for (const l of listeners) l();
  });
}

export function canPromptInstall(): boolean {
  return deferred !== null;
}

export function onInstallPrompt(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

export async function promptInstall(): Promise<boolean> {
  const e = deferred;
  if (!e) return false;
  deferred = null;
  await e.prompt();
  const { outcome } = await e.userChoice;
  for (const l of listeners) l();
  return outcome === 'accepted';
}
