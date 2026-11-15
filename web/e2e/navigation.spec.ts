import { Api } from './support/api';
import { expect, test, useServer } from './support/fixtures';

const box = useServer();

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
