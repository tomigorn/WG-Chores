import { Api, isoDay } from './support/api';
import { expect, longPress, test, useServer } from './support/fixtures';

const box = useServer();

async function setup(context: import('@playwright/test').BrowserContext) {
  const anna = await Api.register(box.server.url, 'Anna');
  const h = await anna.household('WG Luzern');
  await anna.signInContext(context);
  return { anna, h };
}

test('add a chore, it shows in its category with a never badge', async ({ page, context }) => {
  const { anna, h } = await setup(context);
  await page.goto(`${box.server.url}/households/${h.id}`);
  await page.getByRole('link', { name: 'Add chore' }).click();
  await page.getByLabel('Name').fill('Clean the fridge');
  await page.getByLabel('Category').fill('Kitchen');
  await page.getByRole('button', { name: 'every 2 weeks' }).click();
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(new RegExp(`/households/${h.id}$`));
  await expect(page.getByRole('heading', { name: 'Kitchen' })).toBeVisible();
  const row = page.locator('li.row', { hasText: 'Clean the fridge' });
  await expect(row.getByRole('img', { name: /never/i })).toBeVisible();
  const [c] = await anna.chores(h.id);
  expect(c).toMatchObject({ name: 'Clean the fridge', category: 'Kitchen', interval: '2w' });
});

test('tick marks done today, undo removes it again', async ({ page, context }) => {
  const { anna, h } = await setup(context);
  const c = await anna.chore(h.id, 'Take out the bins', 'Kitchen', '1w');
  await page.goto(`${box.server.url}/households/${h.id}`);
  await page.getByRole('button', { name: 'Mark Take out the bins done' }).click();
  const snack = page.getByRole('status');
  await expect(snack).toContainText('Take out the bins– done today');
  await expect.poll(async () => (await anna.completions(c.id)).map((x) => x.done_on)).toEqual([isoDay(0)]);
  await snack.getByRole('button', { name: 'Undo' }).click();
  await expect.poll(async () => (await anna.completions(c.id)).length).toBe(0);
});

test('a double tap on the tick marks once', async ({ page, context }) => {
  const { anna, h } = await setup(context);
  const c = await anna.chore(h.id, 'Sweep the stairs', 'Hall', '1w');
  await page.goto(`${box.server.url}/households/${h.id}`);
  await page.getByRole('button', { name: 'Mark Sweep the stairs done' }).dblclick();
  await expect(page.getByRole('status')).toContainText('Sweep the stairs');
  await expect.poll(async () => (await anna.completions(c.id)).length).toBe(1);
  await page.waitForTimeout(1500);
  expect(await anna.completions(c.id)).toHaveLength(1);
});

test('long press opens the date sheet; yesterday with a note is saved', async ({ page, context }) => {
  const { anna, h } = await setup(context);
  const c = await anna.chore(h.id, 'Water the plants', 'Balcony', '3d');
  await page.goto(`${box.server.url}/households/${h.id}`);
  await longPress(page, page.locator('li.row', { hasText: 'Water the plants' }).locator('.main'));
  const sheet = page.getByRole('dialog');
  await expect(sheet).toContainText('Water the plants');
  await sheet.getByRole('radio', { name: 'Yesterday' }).click();
  await sheet.getByLabel('Note (optional)').fill('the big ones only');
  await sheet.getByRole('button', { name: 'Save' }).click();
  await expect(sheet).toBeHidden();
  await expect.poll(async () => (await anna.completions(c.id)).map((x) => [x.done_on, x.note])).toEqual([[isoDay(1), 'the big ones only']]);
  await expect(page).toHaveURL(new RegExp(`/households/${h.id}$`));
});

test('chore page shows history; an entry can be changed and deleted', async ({ page, context }) => {
  const { anna, h } = await setup(context);
  const c = await anna.chore(h.id, 'Descale the kettle', 'Kitchen', '1m');
  await anna.done(c.id, isoDay(40));
  await anna.done(c.id, isoDay(10), 'with vinegar');
  await page.goto(`${box.server.url}/households/${h.id}`);
  await page.getByRole('link', { name: /Descale the kettle/ }).click();
  await expect(page).toHaveURL(new RegExp(`/chores/${c.id}$`));
  await expect(page.locator('.history li')).toHaveCount(2);
  await expect(page.getByText('“with vinegar”')).toBeVisible();

  await page.locator('.history li').first().getByRole('button').click();
  const sheet = page.getByRole('dialog');
  await sheet.getByLabel('Note (optional)').fill('with citric acid');
  await sheet.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('“with citric acid”')).toBeVisible();

  await page.locator('.history li').last().getByRole('button').click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete entry' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete', exact: true }).click();
  await expect(page.locator('.history li')).toHaveCount(1);
  expect((await anna.completions(c.id)).map((x) => x.note)).toEqual(['with citric acid']);
});

test('edit, archive and restore a chore', async ({ page, context }) => {
  const { anna, h } = await setup(context);
  const c = await anna.chore(h.id, 'Mop the floor', 'Flat', '1w');
  await page.goto(`${box.server.url}/chores/${c.id}`);
  await page.getByRole('link', { name: 'Edit Mop the floor' }).click();
  await page.getByLabel('Name').fill('Mop all floors');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('heading', { name: 'Mop all floors' })).toBeVisible();

  await page.getByRole('link', { name: 'Edit Mop all floors' }).click();
  await page.getByRole('button', { name: 'Archive chore' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Archive', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/households/${h.id}$`));
  await expect(page.getByText('Mop all floors')).toHaveCount(0);

  await page.goto(`${box.server.url}/households/${h.id}/settings`);
  await page.getByRole('button', { name: 'Restore: Mop all floors' }).click();
  await page.goto(`${box.server.url}/households/${h.id}`);
  await expect(page.getByText('Mop all floors')).toBeVisible();
});
