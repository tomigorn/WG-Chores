import { Api } from './support/api';
import { expect, test, useServer } from './support/fixtures';

const box = useServer();

test('rename, add a member by username, remove them', async ({ page, context }) => {
  const anna = await Api.register(box.server.url, 'Anna');
  const ben = await Api.register(box.server.url, 'Ben Example');
  const h = await anna.household('WG Bern');
  await anna.signInContext(context);

  await page.goto(`${box.server.url}/households/${h.id}/settings`);
  await page.getByLabel('Household name').fill('WG Bern Mitte');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('status')).toContainText('Name saved');
  expect((await anna.ok<{ name: string }>('GET', `/api/v1/households/${h.id}`)).name).toBe('WG Bern Mitte');

  await page.getByLabel('Add member').fill(ben.username);
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByText(`@${ben.username}`)).toBeVisible();
  expect((await ben.ok<{ id: number }[]>('GET', '/api/v1/households')).map((x) => x.id)).toContain(h.id);

  await page.getByRole('button', { name: 'Remove Ben Example' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click();
  await expect(page.getByText(`@${ben.username}`)).toHaveCount(0);
  expect(await ben.ok<unknown[]>('GET', '/api/v1/households')).toEqual([]);
});

test('unknown username gets the one refusal text', async ({ page, context }) => {
  const anna = await Api.register(box.server.url, 'Anna');
  const h = await anna.household('WG Basel');
  await anna.signInContext(context);
  await page.goto(`${box.server.url}/households/${h.id}/settings`);
  await page.getByLabel('Add member').fill('nobody-here');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('No user with that name can be added.');
});

test('switch between households from the top bar', async ({ page, context }) => {
  const anna = await Api.register(box.server.url, 'Anna');
  const a = await anna.household('WG Alpha');
  const b = await anna.household('WG Beta');
  await anna.chore(b.id, 'Water plants');
  await anna.signInContext(context);
  await page.goto(`${box.server.url}/households/${a.id}`);
  await page.getByRole('button', { name: 'WG Alpha, Switch household' }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'WG Beta' }).click();
  await expect(page).toHaveURL(new RegExp(`/households/${b.id}$`));
  await expect(page.getByText('Water plants')).toBeVisible();
  await page.goto(box.server.url + '/');
  await expect(page).toHaveURL(new RegExp(`/households/${b.id}$`));
});

test('the last member leaving deletes the household after a confirmation', async ({ page, context }) => {
  const anna = await Api.register(box.server.url, 'Anna');
  const h = await anna.household('WG Chur');
  await anna.signInContext(context);
  await page.goto(`${box.server.url}/households/${h.id}/settings`);
  await page.getByRole('button', { name: 'Leave household' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Delete WG Chur?');
  await dialog.getByRole('button', { name: 'Delete household' }).click();
  await expect(page).toHaveURL(/\/households$/);
  expect(await anna.ok<unknown[]>('GET', '/api/v1/households')).toEqual([]);
});
