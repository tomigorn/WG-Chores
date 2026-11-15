import type { Page } from '@playwright/test';
import { Api, isoDay } from './support/api';
import { expect, markWindow, test, useServer, watchSessionText } from './support/fixtures';

const box = useServer();

async function setup(page: Page, context: import('@playwright/test').BrowserContext, clock = false) {
  const anna = await Api.register(box.server.url, 'Anna');
  const h = await anna.household('WG Thun');
  const c = await anna.chore(h.id, 'Clean the bathroom', 'Bathroom', '1w');
  await anna.done(c.id, isoDay(9));
  await anna.signInContext(context);
  if (clock) await page.clock.install();
  await page.goto(`${box.server.url}/households/${h.id}`);
  await expect(page.getByRole('button', { name: 'Mark Clean the bathroom done' })).toBeVisible();
  return { anna, h, c, tick: page.getByRole('button', { name: 'Mark Clean the bathroom done' }) };
}

/** Idle time for the page's timers, without crossing UTC midnight (the client would then date marks tomorrow). */
function idleMs(): number {
  const now = new Date();
  const midnight = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1);
  return Math.max(0, Math.min(30 * 60_000, midnight - now.getTime() - 5 * 60_000));
}

const waiting = (page: Page) => page.getByRole('button', { name: /done marks? waiting to sync/ });

test('offline tap is queued, counted, and sent exactly once when back online', async ({ page, context }) => {
  const { anna, c, tick } = await setup(page, context);
  await context.setOffline(true);
  await tick.click();
  await expect(waiting(page)).toHaveAccessibleName('1 done marks waiting to sync');
  expect(await anna.completions(c.id)).toHaveLength(1);

  await context.setOffline(false);
  await expect(waiting(page)).toHaveCount(0);
  await expect.poll(async () => (await anna.completions(c.id)).map((x) => x.done_on)).toEqual([isoDay(0), isoDay(9)]);
  await page.reload();
  await expect(page.getByRole('button', { name: 'Mark Clean the bathroom done' })).toBeVisible();
  await page.waitForTimeout(1000);
  expect(await anna.completions(c.id)).toHaveLength(2);
});

test('undo offline of a sent mark survives a reload and is sent when back online', async ({ page, context }) => {
  const { anna, c, tick } = await setup(page, context);
  await page.waitForFunction(() => navigator.serviceWorker?.controller !== null);
  await tick.click();
  await expect.poll(async () => (await anna.completions(c.id)).length).toBe(2);
  await context.setOffline(true);
  await page.getByRole('status').getByRole('button', { name: 'Undo' }).click();
  await expect(waiting(page)).toHaveAccessibleName('1 done marks waiting to sync');
  await page.reload();
  await expect(page.getByText('Clean the bathroom')).toBeVisible();
  expect(await anna.completions(c.id)).toHaveLength(2);
  await context.setOffline(false);
  await expect.poll(async () => (await anna.completions(c.id)).map((x) => x.done_on)).toEqual([isoDay(9)]);
  await expect(waiting(page)).toHaveCount(0);
});

test('the last list stays readable offline, also after a reload', async ({ page, context }) => {
  await setup(page, context);
  await page.waitForFunction(() => navigator.serviceWorker?.controller !== null);
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText('Clean the bathroom')).toBeVisible();
  await expect(page.getByText('Offline – showing the last known list')).toBeVisible();
});

test('other edits offline say to connect, nothing is lost silently', async ({ page, context }) => {
  const { h } = await setup(page, context);
  await page.goto(`${box.server.url}/households/${h.id}/settings`);
  await page.getByLabel('Household name').fill('WG Thun Süd');
  await context.setOffline(true);
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Connect to the internet to save this')).toBeVisible();
});

test('resume_after_idle_offline_and_server_restart', async ({ page, context }) => {
  const { anna, c, tick } = await setup(page, context, true);
  const reloaded = await markWindow(page);
  const badText = await watchSessionText(page);
  const navigations: string[] = [];
  page.on('framenavigated', (f) => f === page.mainFrame() && navigations.push(f.url()));

  await context.setOffline(true);
  await box.server.stop();
  await page.waitForTimeout(3000);
  await page.clock.fastForward(idleMs());
  await box.server.startAgain();
  await context.setOffline(false);
  await page.clock.fastForward(1000);

  await tick.click();
  await expect(page.getByRole('status')).toContainText('done today');
  await expect.poll(async () => (await anna.completions(c.id)).map((x) => x.done_on)).toEqual([isoDay(0), isoDay(9)]);
  await expect(waiting(page)).toHaveCount(0);
  expect(await reloaded(), 'full page reload').toBe(false);
  expect(navigations, 'navigations').toEqual([]);
  expect(await badText(), 'session/expired/reload text').toEqual([]);
  await expect(page).toHaveURL(/\/households\/\d+$/);
});

test('resume: an offline tap while the server is down syncs exactly once after restart', async ({ page, context }) => {
  const { anna, c, tick } = await setup(page, context, true);
  const reloaded = await markWindow(page);
  const badText = await watchSessionText(page);

  await context.setOffline(true);
  await box.server.stop();
  await tick.click();
  await expect(waiting(page)).toHaveAccessibleName('1 done marks waiting to sync');
  await page.clock.fastForward(idleMs());
  await box.server.startAgain();
  await context.setOffline(false);

  await expect(waiting(page)).toHaveCount(0);
  await expect.poll(async () => (await anna.completions(c.id)).length).toBe(2);
  await page.clock.fastForward(61_000);
  await page.waitForTimeout(1000);
  expect(await anna.completions(c.id)).toHaveLength(2);
  expect(await reloaded()).toBe(false);
  expect(await badText()).toEqual([]);
});

test('resume: a tap while the server restarts (network up) is queued and sent by the next refresh', async ({ page, context }) => {
  const { anna, c, tick } = await setup(page, context, true);
  const reloaded = await markWindow(page);
  const badText = await watchSessionText(page);

  await box.server.stop();
  await tick.click();
  await expect(waiting(page)).toHaveAccessibleName('1 done marks waiting to sync');
  await box.server.startAgain();
  await page.clock.fastForward(61_000);

  await expect(waiting(page)).toHaveCount(0);
  await expect.poll(async () => (await anna.completions(c.id)).length).toBe(2);
  await page.clock.fastForward(61_000);
  await page.waitForTimeout(1000);
  expect(await anna.completions(c.id)).toHaveLength(2);
  expect(await reloaded()).toBe(false);
  expect(await badText()).toEqual([]);
});
