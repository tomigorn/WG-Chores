import { Api, PASSWORD, uniqueName } from './support/api';
import { expect, newDeviceContext, test, useServer } from './support/fixtures';

const box = useServer({ OPEN_REGISTRATION: 'false', OIDC_NAME: 'Holy Grail' }, { oidc: true });

test.describe('closed registration', () => {
  let owner: Api;
  let hid: number;

  test.beforeAll(async () => {
    const closed = await new Api(box.server.url).req('POST', '/api/v1/auth/register', { username: uniqueName('x'), password: PASSWORD, display_name: 'X' }); // leak-check: ok
    expect(closed.status).toBe(403);
    const username = uniqueName('anna');
    box.server.createUser(username, 'Anna Example', PASSWORD);
    owner = await Api.login(box.server.url, username);
    hid = (await owner.household('WG Winterthur')).id;
  });

  test('create an invite in the settings, sign up with it, join at once', async ({ page, context, browser }) => {
    await owner.signInContext(context);
    await page.goto(`${box.server.url}/households/${hid}/settings`);
    await page.getByRole('button', { name: 'Create invite link' }).click();
    const link = await page.getByRole('textbox', { name: 'Invite links' }).inputValue();
    expect(link).toMatch(new RegExp(`^${box.server.url}/invite/[A-Za-z0-9_-]{43}$`));
    await expect(page.getByText('by Anna Example')).toBeVisible();

    const guest = await newDeviceContext(browser);
    const p = await guest.newPage();
    await p.goto(link);
    await expect(p.getByRole('heading', { name: 'WG Winterthur' })).toBeVisible();
    await expect(p.getByText('Anna Example invited you to')).toBeVisible();
    await p.getByRole('button', { name: 'Create account' }).click();
    const username = uniqueName('ben');
    await p.getByLabel('Your name').fill('Ben Example');
    await p.getByLabel('Username').fill(username);
    await p.getByLabel('Password').fill(PASSWORD);
    await p.getByRole('button', { name: 'Create account' }).first().click();
    await expect(p).toHaveURL(new RegExp(`/households/${hid}$`));
    await guest.close();

    const members = await owner.ok<{ username: string | null }[]>('GET', `/api/v1/households/${hid}/members`);
    expect(members.map((m) => m.username)).toContain(username);

    const again = await newDeviceContext(browser);
    const q = await again.newPage();
    await q.goto(link);
    await expect(q.getByRole('alert')).toContainText('This invite link was already used.');
    await again.close();
  });

  test('without an invite the login page offers no sign-up', async ({ page }) => {
    await page.goto(box.server.url + '/login');
    await expect(page.getByLabel('Username')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Create account' })).toHaveCount(0);
  });

  test('a signed-in user joins with one tap; cancelled links say so', async ({ page, context }) => {
    const inv = await owner.invite(hid);
    const ben = await Api.register(box.server.url, 'Ben', uniqueName('ben'), { invite: (await owner.invite(hid)).url.split('/').pop()! });
    await ben.ok('POST', `/api/v1/households/${hid}/leave`, {});
    await ben.signInContext(context);
    await page.goto(inv.url);
    await page.getByRole('button', { name: 'Join WG Winterthur' }).click();
    await expect(page).toHaveURL(new RegExp(`/households/${hid}$`));

    const cancelled = await owner.invite(hid);
    await owner.ok('DELETE', `/api/v1/households/${hid}/invites/${cancelled.id}`);
    await page.goto(cancelled.url);
    await expect(page.getByRole('alert')).toContainText('This invite link was cancelled.');
  });

  test('SSO from an invite link joins the household', async ({ page }) => {
    const inv = await owner.invite(hid);
    await box.oidc!.setUser({ sub: uniqueName('sub'), name: 'Cleo Example', preferred_username: uniqueName('cleo'), groups: ['WG'] });
    await page.goto(inv.url);
    await page.getByRole('link', { name: 'Sign in with Holy Grail' }).click();
    await expect(page).toHaveURL(new RegExp(`/(invite/[^/]+|households/${hid})$`));
    if (page.url().includes('/invite/')) await page.getByRole('button', { name: 'Join WG Winterthur' }).click();
    await expect(page).toHaveURL(new RegExp(`/households/${hid}$`));
    const members = await owner.ok<{ display_name: string }[]>('GET', `/api/v1/households/${hid}/members`);
    expect(members.map((m) => m.display_name)).toContain('Cleo Example');
  });
});
