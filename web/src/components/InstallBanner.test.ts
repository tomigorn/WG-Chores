import { describe, expect, it } from 'vitest';
import { DISMISS_DAYS, installBannerMode } from '../lib/install';

const UA = {
  androidChrome: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
  iphoneSafari: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Mobile/15E148 Safari/604.1',
  iphoneChrome: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0.6723.90 Mobile/15E148 Safari/604.1',
  iphoneFirefox: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/132.0 Mobile/15E148 Safari/605.1.15',
  iphoneInstagram: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 Instagram 350.0.0',
  ipadSafari: 'Mozilla/5.0 (iPad; CPU OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
  desktopChrome: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36',
  macSafari: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.1 Safari/605.1.15',
};

const NOW = Date.parse('2026-11-14T10:00:00Z');
const DAY = 86_400_000;

describe('installBannerMode', () => {
  it('banner_mode_matrix', () => {
    const cases: [string, boolean, ReturnType<typeof installBannerMode>][] = [
      [UA.androidChrome, false, 'android'],
      [UA.iphoneSafari, false, 'ios-safari'],
      [UA.ipadSafari, false, 'ios-safari'],
      [UA.iphoneChrome, false, 'ios-other'],
      [UA.iphoneFirefox, false, 'ios-other'],
      [UA.iphoneInstagram, false, 'ios-other'],
      [UA.desktopChrome, false, 'desktop'],
      [UA.macSafari, false, 'desktop'],
      [UA.androidChrome, true, 'none'],
      [UA.iphoneSafari, true, 'none'],
      [UA.desktopChrome, true, 'none'],
    ];
    for (const [ua, standalone, want] of cases) expect([ua, standalone, installBannerMode(ua, standalone, null, NOW)]).toEqual([ua, standalone, want]);
  });

  it('banner_hidden_30_days_after_dismiss', () => {
    expect(DISMISS_DAYS).toBe(30);
    expect(installBannerMode(UA.androidChrome, false, NOW - DAY, NOW)).toBe('none');
    expect(installBannerMode(UA.iphoneSafari, false, NOW - 29 * DAY, NOW)).toBe('none');
    expect(installBannerMode(UA.iphoneSafari, false, NOW - 30 * DAY, NOW)).toBe('ios-safari');
    expect(installBannerMode(UA.desktopChrome, false, NOW - 400 * DAY, NOW)).toBe('desktop');
    expect(installBannerMode(UA.androidChrome, false, NOW + DAY, NOW)).toBe('android');
  });
});
