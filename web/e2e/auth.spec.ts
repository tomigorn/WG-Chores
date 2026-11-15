import { Api, PASSWORD, uniqueName } from './support/api';
import { expect, test, useServer } from './support/fixtures';

test.describe('local accounts with SSO', () => {
  const box = useServer({ OIDC_NAME: 'Holy Grail', OIDC_REQUIRED_GROUP: 'WG' }, { oidc: true });

  test('register, sign out, sign in again opens the last household', async ({ page }) => {
    const username = uniqueName('anna');
    await page.goto(box.server.url + '/');
    await expect(page).toHaveURL(/\/login$/);
    await page.getByRole('button', { name: 'Create account' }).click();
    await page.getByLabel('Your name').fill('Anna');
    await page.getByLabel('Username').fill(username);
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Create account' }).first().click();
    await expect(page).toHaveURL(/\/households$/);
    await expect(page.getByText('No household yet')).toBeVisible();

    await page.getByRole('button', { name: 'New household' }).click();
    await page.getByLabel('New household').fill('WG Zürich');
    await page.getByRole('button', { name: 'Create' }).click();
    await expect(page).toHaveURL(/\/households\/\d+$/);
    const hhUrl = page.url();

    await page.getByRole('button', { name: 'Menu' }).click();
    await page.getByRole('menuitem', { name: 'Sign out' }).click();
    await expect(page).toHaveURL(/\/login$/);

    await page.getByLabel('Username').fill(username);
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page).toHaveURL(hhUrl);
  });

  test('wrong password shows a message and stays on the login page', async ({ page }) => {
    const a = await Api.register(box.server.url);
    await page.goto(box.server.url + '/login');
    await page.getByLabel('Username').fill(a.username);
    await page.getByLabel('Password').fill('not the right password');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('alert')).toHaveText('Wrong username or password.');
    await expect(page).toHaveURL(/\/login$/);
  });

  test('local account deletion asks for the password', async ({ page, context }) => {
    const a = await Api.register(box.server.url);
    await a.household('Solo WG');
    await a.signInContext(context);
    await page.goto(box.server.url + '/account');
    await page.getByRole('button', { name: 'Delete account' }).click();
    const sheet = page.getByRole('dialog');
    await expect(sheet).toContainText('Households where you are the only member are deleted too.');
    await sheet.getByLabel('Enter your password to confirm').fill('not the right password');
    await sheet.getByRole('button', { name: 'Delete my account' }).click();
    await expect(sheet.getByRole('alert')).toHaveText('The current password is wrong.');
    await sheet.getByLabel('Enter your password to confirm').fill(PASSWORD);
    await sheet.getByRole('button', { name: 'Delete my account' }).click();
    await expect(page).toHaveURL(/\/login$/);
    const again = await new Api(box.server.url).req('POST', '/api/v1/auth/login', { username: a.username, password: PASSWORD }); // leak-check: ok
    expect(again.status).toBe(401);
  });

  test('a deep link returns to the wanted page after sign-in', async ({ page }) => {
    const a = await Api.register(box.server.url);
    const h = await a.household('Deep link WG');
    await page.goto(`${box.server.url}/households/${h.id}/settings`);
    await expect(page).toHaveURL(/\/login$/);
    await page.getByLabel('Username').fill(a.username);
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/households/${h.id}/settings$`));
  });

  test('SSO sign-in via the provider creates the account', async ({ page }) => {
    await box.oidc!.setUser({ sub: uniqueName('sub'), name: 'Jane Doe', preferred_username: uniqueName('jane'), groups: ['WG'] });
    await page.goto(box.server.url + '/login');
    await page.getByRole('link', { name: 'Sign in with Holy Grail' }).click();
    await expect(page).toHaveURL(/\/households$/);
    await page.goto(box.server.url + '/account');
    await expect(page.getByText('Jane Doe')).toBeVisible();
  });

  test('app sign-in asks once in the browser before the code goes to the app', async ({ page }) => {
    const csp: string[] = [];
    page.on('console', (m) => {
      if (/Content Security Policy/i.test(m.text())) csp.push(m.text());
    });
    const start = `${box.server.url}/auth/oidc/start?client=android&challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`;
    await box.oidc!.setUser({ sub: uniqueName('sub'), name: 'Jane Doe', preferred_username: uniqueName('jane'), groups: ['WG'] });
    await page.goto(start);
    await expect(page.getByRole('heading', { name: 'Sign in to the WG Chores app?' })).toBeVisible();
    await expect(page.locator('strong', { hasText: 'Jane Doe' })).toBeVisible();
    const styled = await page.getByRole('button', { name: 'Sign in to the app' }).evaluate((b) => getComputedStyle(b).borderRadius);
    expect(styled).not.toBe('0px');
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByText('You can close this tab.')).toBeVisible();

    await page.goto(start);
    const answer = page.waitForResponse((r) => r.url().endsWith('/auth/app/confirm'));
    await page.getByRole('button', { name: 'Sign in to the app' }).click();
    const r = await answer;
    expect(r.status()).toBe(303);
    expect(r.headers()['location']).toMatch(/^wgchores:\/\/auth\?code=[\w-]{43}$/);
    expect(csp).toEqual([]);
  });

  test('SSO user without the required group is refused with a clear message', async ({ page }) => {
    await box.oidc!.setUser({ sub: uniqueName('sub'), name: 'Jane Doe', groups: ['Guests'] });
    await page.goto(box.server.url + '/login');
    await page.getByRole('link', { name: 'Sign in with Holy Grail' }).click();
    await expect(page).toHaveURL(/\/login\?error=not_allowed/);
    await expect(page.getByRole('alert')).toContainText('not allowed on this server');
    const me = await page.evaluate(() => fetch('/api/v1/me', { headers: { 'X-WGC': '1' } }).then((r) => r.status));
    expect(me).toBe(401);
  });
});

test.describe('SSO only', () => {
  const box = useServer({ LOCAL_ACCOUNTS: 'false', OIDC_NAME: 'Holy Grail' }, { oidc: true });

  test('no password form, and the local login API is refused', async ({ page }) => {
    await page.goto(box.server.url + '/login');
    await expect(page.getByRole('link', { name: 'Sign in with Holy Grail' })).toBeVisible();
    await expect(page.getByLabel('Password')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Create account' })).toHaveCount(0);
    const r = await new Api(box.server.url).req('POST', '/api/v1/auth/login', { username: 'anna', password: PASSWORD }); // leak-check: ok
    expect(r.status).toBe(403);
  });
});
