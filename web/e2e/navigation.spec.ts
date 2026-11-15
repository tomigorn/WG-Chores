import type { Locator, Page } from '@playwright/test';
import { Api } from './support/api';
import { expect, test, useServer } from './support/fixtures';

const box = useServer();

const top = (l: Locator) => l.evaluate((el) => el.getBoundingClientRect().top);

async function longList(context: import('@playwright/test').BrowserContext) {
  const anna = await Api.register(box.server.url, 'Anna');
  const h = await anna.household('WG Luzern');
  for (let i = 1; i <= 36; i++) await anna.chore(h.id, `Chore ${String(i).padStart(2, '0')}`, `Room ${Math.ceil(i / 9)}`, '1w');
  await anna.signInContext(context);
  return { anna, h };
}

async function openFarDown(page: Page, name: string) {
  const link = page.locator('li.row', { hasText: name }).locator('a.main');
  await link.scrollIntoViewIfNeeded();
  await page.evaluate(() => scrollBy(0, -150));
  await expect.poll(() => page.evaluate(() => scrollY)).toBeGreaterThan(300);
  const before = await top(link);
  await link.click();
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
  return { link, before };
}

test('back gesture returns to the same place in the chore list', async ({ page, context }) => {
  const { h } = await longList(context);
  await page.goto(`${box.server.url}/households/${h.id}`);
  const { link, before } = await openFarDown(page, 'Chore 30');
  await page.goBack();
  await expect(link).toBeVisible();
  await expect.poll(async () => Math.abs((await top(link)) - before)).toBeLessThanOrEqual(4);
});

test('the in-app back arrow returns to the same place too', async ({ page, context }) => {
  const { h } = await longList(context);
  await page.goto(`${box.server.url}/households/${h.id}`);
  const { link, before } = await openFarDown(page, 'Chore 27');
  const length = await page.evaluate(() => history.length);
  await page.getByRole('link', { name: 'Back' }).click();
  await expect(link).toBeVisible();
  await expect.poll(async () => Math.abs((await top(link)) - before)).toBeLessThanOrEqual(4);
  expect(await page.evaluate(() => history.length)).toBe(length);
});

test('offline the list comes from the cache at the same scroll position, the refresh keeps it', async ({ page, context }) => {
  const { h } = await longList(context);
  await page.goto(`${box.server.url}/households/${h.id}`);
  const link = page.locator('li.row', { hasText: 'Chore 33' }).locator('a.main');
  await link.scrollIntoViewIfNeeded();
  const y = await page.evaluate(() => scrollY);
  expect(y).toBeGreaterThan(300);
  await link.click();
  await expect(page.getByRole('heading', { level: 1, name: 'Chore 33' })).toBeVisible();
  await context.setOffline(true);
  await page.goBack();
  await expect(page.getByText('Offline – showing the last known list')).toBeAttached();
  await expect.poll(async () => Math.abs((await page.evaluate(() => scrollY)) - y)).toBeLessThanOrEqual(2);
  await expect(link).toBeInViewport();
  const offlineTop = await top(link);
  await context.setOffline(false);
  await expect(page.getByText('Offline – showing the last known list')).toHaveCount(0);
  await page.waitForTimeout(500);
  expect(Math.abs((await top(link)) - offlineTop)).toBeLessThanOrEqual(2);
});

test('a fresh view from a link starts at the top', async ({ page, context }) => {
  const { h } = await longList(context);
  await page.goto(`${box.server.url}/households/${h.id}`);
  await openFarDown(page, 'Chore 30');
  await expect.poll(() => page.evaluate(() => scrollY)).toBe(0);
});

test('back returns to the same place in the households list', async ({ page, context }) => {
  const anna = await Api.register(box.server.url, 'Anna');
  for (let i = 1; i <= 24; i++) await anna.household(`Flat ${String(i).padStart(2, '0')}`);
  await anna.signInContext(context);
  await page.goto(`${box.server.url}/households`);
  const link = page.locator('a.open', { hasText: 'Flat 20' });
  await link.scrollIntoViewIfNeeded();
  await page.evaluate(() => scrollBy(0, -120));
  await expect.poll(() => page.evaluate(() => scrollY)).toBeGreaterThan(300);
  const before = await top(link);
  await link.click();
  await expect(page).toHaveURL(/\/households\/\d+$/);
  await page.goBack();
  await expect(link).toBeVisible();
  await expect.poll(async () => Math.abs((await top(link)) - before)).toBeLessThanOrEqual(4);
});

test('other date is a calendar icon button that opens the date sheet', async ({ page, context }) => {
  const anna = await Api.register(box.server.url, 'Anna');
  const h = await anna.household('WG Luzern');
  const c = await anna.chore(h.id, 'Water the plants', 'Balcony', '3d');
  await anna.signInContext(context);
  await page.goto(`${box.server.url}/chores/${c.id}`);
  const other = page.locator('footer').getByRole('button', { name: 'Other date…' });
  const done = page.locator('footer').getByRole('button', { name: 'Done today' });
  await expect(other).toBeVisible();
  await expect(other).toHaveText('');
  await expect(other.locator('svg')).toHaveCount(1);
  const o = (await other.boundingBox())!;
  const d = (await done.boundingBox())!;
  expect(Math.abs(o.height - d.height)).toBeLessThanOrEqual(1);
  expect(Math.abs(o.width - o.height)).toBeLessThanOrEqual(1);
  expect(d.width).toBeGreaterThan(o.width * 3);
  await other.click();
  await expect(page.getByRole('dialog')).toContainText('Water the plants');
});
