import { devices, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { Api, isoDay } from './support/api';
import { expect, test, useServer } from './support/fixtures';
import { checkLayout, setRootFont, settle, shownText } from './support/layout';
import { OUT } from './support/server';

const SHOTS = path.join(OUT, 'layout');
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

/** Fresh, identical data per test (the tag keeps usernames unique on the worker's server). */
async function makeSeed(tag: string): Promise<Seed> {
  const base = box.server.url;
  const anna = await Api.register(base, LONG_PERSON, `anna-barbara-zimmermann-${tag}`);
  const ben = await Api.register(base, 'Ben', `ben-${tag}`);
  const h = await anna.household(LONG_HOUSEHOLD);
  await anna.household('WG Bern');
  await anna.addMember(h.id, ben.username);
  const long = await anna.chore(h.id, LONG_CHORE, LONG_CATEGORY, '1w');
  for (const [d, note, who] of [
    [3, 'Mit dem neuen Entfetter, der riecht viel weniger stark als der alte und wirkt besser', anna],
    [12, null, ben],
    [20, 'Nur die Platten', anna],
    [31, null, ben],
    [400, null, anna],
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

type Screen = { name: string; signedOut?: boolean; phoneOnly?: boolean; open: (p: Page, s: Seed, base: string) => Promise<void>; after?: (p: Page) => Promise<void> };

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
  { name: 'privacy', signedOut: true, open: async (p, _s, b) => void (await p.goto(b + '/privacy'), await p.locator('h1').waitFor()) },
  { name: 'account-deletion', signedOut: true, open: async (p, _s, b) => void (await p.goto(b + '/account-deletion'), await p.locator('h1').waitFor()) },
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
      // the next goto would abort a DELETE still on its way
      await Promise.all([
        p.waitForResponse((r) => r.request().method() === 'DELETE' && r.url().includes('/api/v1/completions/')),
        p.locator('.snack .act').first().click(),
      ]);
      await p.locator('.snack').waitFor({ state: 'detached' });
      // undone: the next screens see the same data as every other width
      await expect(row(p, LONG_CHORE).locator('.badge')).toHaveText(/^3 /);
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
    phoneOnly: true,
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
    userAgent: width >= 1024 ? devices['Desktop Chrome'].userAgent : devices['iPhone 13'].userAgent,
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
  const s = await makeSeed(`${lang}${width}`);
  const base = box.server.url;
  const inCtx = await contextFor(browser, width, lang, s.anna);
  const outCtx = await contextFor(browser, width, lang, null);
  const pages = { in: await inCtx.newPage(), out: await outCtx.newPage() };
  const dir = path.join(SHOTS, `${lang}-${width}`);
  mkdirSync(dir, { recursive: true });
  const all: string[] = [];
  for (const screen of SCREENS) {
    // the desktop user agent gets no banner without the browser's install prompt
    if (screen.phoneOnly && width >= 1024) continue;
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
  const seed = await makeSeed('long');
  const ctx = await contextFor(browser, 320, 'en', seed.anna);
  const p = await ctx.newPage();
  await p.goto(`${box.server.url}/households/${seed.hid}`);
  const r = row(p, LONG_CHORE);
  await r.waitFor();
  await setRootFont(p, 200);
  await settle(p);
  expect(LONG_CHORE.length).toBe(60);
  expect(LONG_CATEGORY.length).toBe(40);
  // the name is readable: at least 12 of its characters on screen, at most two lines, whole words
  const name = await r.locator('.name').evaluate(shownText);
  expect(name.shown, `visible part of the chore name: "${name.text}"`).toBeGreaterThanOrEqual(12);
  expect(name.lines).toBeLessThanOrEqual(2);
  expect(LONG_CHORE.replace(/\s/g, '').startsWith(name.text)).toBe(true);
  // the badge keeps number and unit together on one line, fully visible, and the tick stays tappable inside the screen
  const b = await r.locator('.badge').evaluate((el) => {
    const range = document.createRange();
    range.selectNodeContents(el);
    const tops = new Set([...range.getClientRects()].map((x) => Math.round(x.top)));
    return { lines: tops.size, clipped: el.scrollWidth > el.clientWidth + 1, text: el.textContent };
  });
  expect(b).toEqual({ lines: 1, clipped: false, text: '3 d' });
  const tick = (await r.locator('button.done').boundingBox())!;
  expect(tick.x + tick.width).toBeLessThanOrEqual(320);
  expect(Math.min(tick.width, tick.height)).toBeGreaterThanOrEqual(40);
  // the 40-character category header and the household name in the top bar stay readable too
  const cat = await p.getByRole('heading', { name: LONG_CATEGORY }).evaluate(shownText);
  expect(cat.lines).toBeLessThanOrEqual(2);
  expect(cat.shown).toBeGreaterThanOrEqual(12);
  const title = await p.locator('header .hh .name').evaluate(shownText);
  expect(title.shown, `visible household name: "${title.text}"`).toBeGreaterThanOrEqual(8);
  expect(await p.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
  await p.screenshot({ path: path.join(SHOTS, 'long_names_320px_200pct.png'), fullPage: true });
  await ctx.close();
});

for (const lang of ['en', 'de'] as const) {
  test(`interval_chips_320px_200pct_${lang}`, async ({ browser }) => {
    const seed = await makeSeed(`chips${lang}`);
    const ctx = await contextFor(browser, 320, lang, seed.anna);
    const p = await ctx.newPage();
    await p.goto(`${box.server.url}/chores/${seed.longChore}/edit`);
    await p.locator('#chore-name').waitFor();
    await setRootFont(p, 200);
    await settle(p);
    // every chip label stays on one line, unclipped and inside the screen
    const chips = await p.locator('.chips button').evaluateAll((els) =>
      els.map((el) => {
        const range = document.createRange();
        range.selectNodeContents(el);
        const tops = new Set([...range.getClientRects()].map((x) => Math.round(x.top)));
        const r = el.getBoundingClientRect();
        return { text: el.textContent!.trim(), lines: tops.size, clipped: el.scrollWidth > el.clientWidth + 1, inside: r.left >= 0 && r.right <= 320 };
      }),
    );
    const words = lang === 'en' ? ['every month', 'every 2 months', 'every 3 months'] : ['jeden Monat', 'alle 2 Monate', 'alle 3 Monate'];
    expect(chips.map((c) => c.text).slice(3, 6)).toEqual(words);
    expect(chips).toHaveLength(8);
    for (const c of chips) expect(c, c.text).toMatchObject({ lines: 1, clipped: false, inside: true });
    expect(await p.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    await p.screenshot({ path: path.join(SHOTS, `interval_chips_320px_200pct_${lang}.png`), fullPage: true });
    await ctx.close();
  });
}
