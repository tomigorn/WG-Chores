import { test as base, expect, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test';
import { FakeOidc, Server } from './server';

export { expect };

/** Fails a test on any browser dialog (alert/confirm/prompt) or uncaught page error. */
export const test = base.extend<{ guard: { dialogs: string[]; errors: string[] } }>({
  guard: [
    async ({ page }, use) => {
      const g = { dialogs: [] as string[], errors: [] as string[] };
      page.on('dialog', (d) => {
        g.dialogs.push(`${d.type()}: ${d.message()}`);
        void d.dismiss();
      });
      page.on('pageerror', (e) => g.errors.push(e.message));
      await use(g);
      expect(g.dialogs, 'browser dialogs').toEqual([]);
      expect(g.errors, 'uncaught page errors').toEqual([]);
    },
    { auto: true },
  ],
});

/** One server (and optional fake OIDC provider) per worker of a spec file or describe block. */
export function useServer(env: Record<string, string> = {}, opts: { oidc?: boolean } = {}) {
  const box: { server: Server; oidc: FakeOidc | null } = { server: null as unknown as Server, oidc: null };
  test.beforeAll(async () => {
    box.oidc = opts.oidc ? await FakeOidc.start() : null;
    box.server = await Server.start({ ...(box.oidc?.env ?? {}), ...env });
  });
  test.afterAll(async () => {
    await box.server?.dispose();
    await box.oidc?.stop();
  });
  return box;
}

/** A real touch long press on touch projects (CDP touch events), else the context menu (right click). */
export async function longPress(page: Page, target: Locator, ms = 700): Promise<void> {
  const touch = await page.evaluate(() => navigator.maxTouchPoints > 0);
  if (!touch) return target.click({ button: 'right' });
  const box = await target.boundingBox();
  if (!box) throw new Error('long press target not visible');
  const cdp = await page.context().newCDPSession(page);
  const point = { x: box.x + Math.min(40, box.width / 2), y: box.y + box.height / 2 };
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [point] });
  await page.waitForTimeout(ms);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
}

/** Marks the window; `reloaded()` is true when a full page load replaced it since. */
export async function markWindow(page: Page): Promise<() => Promise<boolean>> {
  await page.evaluate(() => ((window as unknown as { __wgcMark: number }).__wgcMark = 1));
  return async () => !(await page.evaluate(() => (window as unknown as { __wgcMark?: number }).__wgcMark === 1));
}

/** Records every text that ever appears in the page which hints at a lost session. */
export async function watchSessionText(page: Page): Promise<() => Promise<string[]>> {
  await page.evaluate(() => {
    const w = window as unknown as { __wgcBad: string[] };
    w.__wgcBad = [];
    const bad = /session (has )?expired|session ended|reload the page|please reload|sign in again|log in again|sitzung|seite neu laden|erneut anmelden/i;
    const scan = (n: Node) => {
      const text = (n.textContent ?? '').trim();
      if (text && bad.test(text)) w.__wgcBad.push(text.slice(0, 200));
    };
    scan(document.body);
    new MutationObserver((ms) => ms.forEach((m) => (m.type === 'characterData' ? scan(m.target) : m.addedNodes.forEach(scan)))).observe(document.body, {
      subtree: true,
      childList: true,
      characterData: true,
    });
  });
  return () => page.evaluate(() => (window as unknown as { __wgcBad: string[] }).__wgcBad);
}

/** A second, signed-out browser with the same device emulation as the running project. */
export function newDeviceContext(browser: Browser): Promise<BrowserContext> {
  const u = test.info().project.use;
  return browser.newContext({
    viewport: u.viewport,
    userAgent: u.userAgent,
    deviceScaleFactor: u.deviceScaleFactor,
    isMobile: u.isMobile,
    hasTouch: u.hasTouch,
    locale: u.locale,
    timezoneId: u.timezoneId,
  });
}
