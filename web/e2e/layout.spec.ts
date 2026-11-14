import { devices, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Api, isoDay, uniqueName } from './support/api';
import { expect, test, useServer } from './support/fixtures';
import { checkLayout, setRootFont, settle } from './support/layout';

const SHOTS = path.join(os.homedir(), '.cache/claude-work/wgc-layout');
const WIDTHS = [320, 375, 414, 768, 1280];
const THEMES = ['light', 'dark'] as const;
const FONTS = [100, 200];

// Review Focus 5: a 60-character chore name and a 40-character Swiss German category
export const LONG_CHORE = 'Backofen, Herdplatten & Dunstabzugshaube gründlich entfetten';
export const LONG_CATEGORY = 'Chuchi, Abwäsch und Chüelschrank-Putzete';
const LONG_HOUSEHOLD = 'WG Zürich Kreis 4 – Langstrasse 123, Hinterhaus 2. Stock re.';
const LONG_PERSON = 'Anna-Barbara Zimmermann-Oberholzer von Gunten-Rüegsegger-Bär';

test.describe.configure({ mode: 'parallel' });

const box = useServer({ OIDC_NAME: 'Holy Grail Single Sign-On', OIDC_ACCOUNT_URL: 'https://sso.example.com/if/user/' }, { oidc: true });

interface Seed {
  anna: Api;
  hid: number;
  longChore: number;
  invite: string;
}

let seed: Seed | null = null;

async function makeSeed(): Promise<Seed> {
  const base = box.server.url;
  const anna = await Api.register(base, LONG_PERSON, uniqueName('anna-barbara-zimmermann'));
  const ben = await Api.register(base, 'Ben', uniqueName('ben'));
  const h = await anna.household(LONG_HOUSEHOLD);
  await anna.household('WG Bern');
  await anna.addMember(h.id, ben.username);
  const long = await anna.chore(h.id, LONG_CHORE, LONG_CATEGORY, '1w');
  for (const [d, note, who] of [
    [3, 'Mit dem neuen Entfetter, der riecht viel weniger stark als der alte und wirkt besser', anna],
    [12, null, ben],
    [20, 'Nur die Platten', anna],
    [31, null, ben],
  ] as const) await who.done(long.id, isoDay(d), note ?? undefined);
  // one chore per due level, and a four-digit day count
  const levels: [string, string, number | null][] = [
    ['Altglas', '1w', 1],
    ['Pflanzen giessen', '3d', 2],
    ['Bad putzen', '1w', 6],
    ['Staubsaugen', '1w', 8],
    ['Fenster putzen', '1m', 50],
    ['Kühlschrank abtauen', '3m', 300],
    ['Entkalken', '3d', 1800],
    ['Vorhänge waschen', '6m', null],
  ];
  for (const [name, interval, ago] of levels) {
    const c = await anna.chore(h.id, name, name === 'Altglas' ? 'Keller' : name === 'Pflanzen giessen' ? LONG_CATEGORY : 'Wohnung', interval);
    if (ago !== null) await ben.done(c.id, isoDay(ago));
  }
  await anna.chore(h.id, 'Ohne Kategorie, ohne Intervall');
  const archived = await anna.chore(h.id, 'Weihnachtsdeko auf dem Estrich sortieren und beschriften', LONG_CATEGORY);
  await anna.ok('POST', `/api/v1/chores/${archived.id}/archive`);
  await anna.invite(h.id);
  const inv = await ben.invite(h.id);
  return { anna, hid: h.id, longChore: long.id, invite: inv.url };
}

type Screen = { name: string; signedOut?: boolean; open: (p: Page, s: Seed, base: string) => Promise<void>; after?: (p: Page) => Promise<void> };

const row = (p: Page, name: string) => p.locator('li.row', { hasText: name });

const SCREENS: Screen[] = [
  { name: 'login', signedOut: true, open: async (p, _s, b) => void (await p.goto(b + '/login'), await p.locator('#username').waitFor()) },
  {
    name: 'login-register',
    signedOut: true,
    open: async (p, _s, b) => {
      await p.goto(b + '/login');
      await p.getByRole('button', { name: /Create account|Konto erstellen/ }).click();
      await p.locator('#display-name').waitFor();
    },
  },
  { name: 'invite-signed-out', signedOut: true, open: async (p, s) => void (await p.goto(s.invite), await p.locator('#username').waitFor()) },
  { name: 'households', open: async (p, _s, b) => void (await p.goto(b + '/households'), await p.locator('.hh-row').first().waitFor()) },
  { name: 'households-new', open: async (p, _s, b) => void (await p.goto(b + '/households?new=1'), await p.locator('#hh-name').waitFor()) },
  { name: 'household', open: async (p, s, b) => void (await p.goto(`${b}/households/${s.hid}`), await row(p, LONG_CHORE).waitFor()) },
  {
    name: 'menu',
    open: async (p, s, b) => {
      await p.goto(`${b}/households/${s.hid}`);
      await p.locator('[data-menu-button]').click();
      await p.getByRole('menu').waitFor();
    },
  },
  {
    name: 'household-switcher',
    open: async (p, s, b) => {
      await p.goto(`${b}/households/${s.hid}`);
      await p.locator('header .hh').click();
      await p.locator('dialog .hh-item').first().waitFor();
    },
  },
  {
    name: 'date-sheet',
    open: async (p, s, b) => {
      await p.goto(`${b}/households/${s.hid}`);
      await row(p, LONG_CHORE).locator('.main').click({ button: 'right' });
      await p.locator('dialog #done-note').waitFor();
    },
  },
  {
    name: 'snackbar',
    open: async (p, s, b) => {
      await p.goto(`${b}/households/${s.hid}`);
      await row(p, LONG_CHORE).locator('button.done').click();
      await p.locator('.snack').hover();
    },
    after: async (p) => {
      await p.locator('.snack .act').first().click();
      await p.locator('.snack').waitFor({ state: 'detached' });
    },
  },
  {
    name: 'waiting-to-sync',
    open: async (p, s, b) => {
      await p.goto(`${b}/households/${s.hid}`);
      await p.context().setOffline(true);
      await row(p, 'Altglas').locator('button.done').click();
      await p.locator('header .chip').waitFor();
      await p.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
      await p.mouse.move(0, 0);
      await p.locator('.snack').waitFor({ state: 'detached', timeout: 15_000 });
    },
    after: async (p) => {
      await p.context().setOffline(false);
      await p.locator('header .chip').waitFor({ state: 'detached' });
    },
  },
  {
    name: 'install-guide',
    open: async (p, s, b) => {
      await p.goto(`${b}/households/${s.hid}`);
      await p.locator('.banner .btn-primary').click();
      await p.locator('dialog .guide').waitFor();
    },
  },
  { name: 'chore', open: async (p, s, b) => void (await p.goto(`${b}/chores/${s.longChore}`), await p.locator('.history li').first().waitFor()) },
  {
    name: 'history-entry-sheet',
    open: async (p, s, b) => {
      await p.goto(`${b}/chores/${s.longChore}`);
      await p.locator('.history li button').first().click();
      await p.locator('dialog #done-note').waitFor();
    },
  },
  { name: 'chore-edit', open: async (p, s, b) => void (await p.goto(`${b}/chores/${s.longChore}/edit`), await p.locator('#chore-name').waitFor()) },
  {
    name: 'chore-edit-rename-category',
    open: async (p, s, b) => {
      await p.goto(`${b}/chores/${s.longChore}/edit`);
      await p.locator('#chore-category').fill('Chuchi, Abwäsch und Chüelschrank-Putzen');
      await p.locator('#rename-all').waitFor();
    },
  },
  { name: 'chore-new', open: async (p, s, b) => void (await p.goto(`${b}/households/${s.hid}/new-chore`), await p.locator('#chore-name').waitFor()) },
  { name: 'household-settings', open: async (p, s, b) => void (await p.goto(`${b}/households/${s.hid}/settings`), await p.locator('li.invite').first().waitFor()) },
  {
    name: 'household-settings-leave',
    open: async (p, s, b) => {
      await p.goto(`${b}/households/${s.hid}/settings`);
      await p.locator('.leave .btn-danger').click();
      await p.locator('dialog .btn').first().waitFor();
    },
  },
  { name: 'invite-signed-in', open: async (p, s) => void (await p.goto(s.invite), await p.locator('main .btn-primary').waitFor()) },
  { name: 'account', open: async (p, _s, b) => void (await p.goto(b + '/account'), await p.locator('main input').first().waitFor()) },
  { name: 'settings', open: async (p, _s, b) => void (await p.goto(b + '/settings'), await p.locator('input[role=switch]').first().waitFor()) },
];

async function contextFor(browser: Browser, width: number, lang: string, signedIn: Api | null): Promise<BrowserContext> {
  const phone = width <= 414;
  const ctx = await browser.newContext({
    viewport: { width, height: phone ? 700 : 900 },
    userAgent: devices['iPhone 13'].userAgent,
    isMobile: phone,
    hasTouch: phone,
    deviceScaleFactor: phone ? 2 : 1,
    reducedMotion: 'reduce',
    locale: lang === 'de' ? 'de-CH' : 'en-GB',
    timezoneId: 'UTC',
  });
  await ctx.addInitScript((l) => localStorage.setItem('wgc.lang', l), lang);
  if (signedIn) await signedIn.signInContext(ctx);
  return ctx;
}

async function runMatrix(browser: Browser, width: number, lang: 'en' | 'de', themes: readonly ('light' | 'dark')[] = THEMES) {
  seed ??= await makeSeed();
  const s = seed;
  const base = box.server.url;
  const inCtx = await contextFor(browser, width, lang, s.anna);
  const outCtx = await contextFor(browser, width, lang, null);
  const pages = { in: await inCtx.newPage(), out: await outCtx.newPage() };
  const dir = path.join(SHOTS, `${lang}-${width}`);
  mkdirSync(dir, { recursive: true });
  const all: string[] = [];
  for (const screen of SCREENS) {
    const p = screen.signedOut ? pages.out : pages.in;
    await screen.open(p, s, base);
    for (const theme of themes) {
      await p.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' });
      for (const font of FONTS) {
        await setRootFont(p, font);
        await settle(p);
        const problems = await checkLayout(p, { touchTargets: font === 100, minTarget: 40 });
        all.push(...problems.map((x) => `${lang} ${width}px ${theme} ${font}% ${screen.name}: ${x}`));
        await p.screenshot({ path: path.join(dir, `${screen.name}-${theme}-${font}.png`), fullPage: true, animations: 'disabled' });
      }
    }
    await setRootFont(p, 100);
    await screen.after?.(p);
  }
  await inCtx.close();
  await outCtx.close();
  writeFileSync(path.join(dir, 'problems.txt'), all.join('\n') + '\n');
  expect(all, `layout problems (screenshots in ${dir})`).toEqual([]);
}

for (const width of WIDTHS) {
  test(`every screen at ${width}px, light and dark, 100 % and 200 % text`, async ({ browser }) => {
    test.setTimeout(240_000);
    await runMatrix(browser, width, 'en');
  });
}

test('every screen in German at 320px, 100 % and 200 % text', async ({ browser }) => {
  test.setTimeout(240_000);
  await runMatrix(browser, 320, 'de', ['light']);
});

test('long_names_320px_200pct', async ({ browser }) => {
  seed ??= await makeSeed();
  const ctx = await contextFor(browser, 320, 'en', seed.anna);
  const p = await ctx.newPage();
  await p.goto(`${box.server.url}/households/${seed.hid}`);
  const r = row(p, LONG_CHORE);
  await r.waitFor();
  await setRootFont(p, 200);
  await settle(p);
  const name = r.locator('.name');
  const badge = r.locator('.badge');
  expect(LONG_CHORE.length).toBe(60);
  expect(LONG_CATEGORY.length).toBe(40);
  // the badge keeps number and unit together on one line, fully visible, and the tick stays tappable
  const b = await badge.evaluate((el) => {
    const range = document.createRange();
    range.selectNodeContents(el);
    const tops = new Set([...range.getClientRects()].map((x) => Math.round(x.top)));
    return { lines: tops.size, clipped: el.scrollWidth > el.clientWidth + 1, text: el.textContent };
  });
  expect(b).toEqual({ lines: 1, clipped: false, text: '3 d' });
  const n = await name.evaluate((el) => ({ h: el.getBoundingClientRect().height, lh: parseFloat(getComputedStyle(el).lineHeight), w: el.clientWidth }));
  expect(n.h).toBeLessThanOrEqual(1.5 * n.lh);
  expect(n.w).toBeGreaterThan(40);
  const tick = await r.locator('button.done').boundingBox();
  expect(tick!.x + tick!.width).toBeLessThanOrEqual(320);
  const cat = p.getByRole('heading', { name: LONG_CATEGORY });
  const c = await cat.evaluate((el) => {
    const range = document.createRange();
    range.selectNodeContents(el);
    return { lines: new Set([...range.getClientRects()].map((x) => Math.round(x.top))).size };
  });
  expect(c.lines).toBe(1);
  expect(await p.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  await p.screenshot({ path: path.join(SHOTS, 'long_names_320px_200pct.png'), fullPage: true });
  await ctx.close();
});
