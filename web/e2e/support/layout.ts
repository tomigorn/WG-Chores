import type { Page } from '@playwright/test';

export interface LayoutOptions {
  touchTargets: boolean;
  minTarget: number;
}

/** Runs in the page: returns one line per layout problem (wrapping, overflow, overlap, small targets). */
function inspect(o: LayoutOptions): string[] {
  const problems: string[] = [];
  const INTERACTIVE = 'a[href], button, input:not([type=hidden]), select, textarea, summary, [role=button], [role=switch], [role=radio], [role=menuitem], [role=menuitemradio], [tabindex]:not([tabindex="-1"])';

  const label = (el: Element) => {
    const name = el.getAttribute('aria-label') ?? (el as HTMLElement).innerText ?? el.getAttribute('placeholder') ?? '';
    const cls = typeof el.className === 'string' && el.className ? '.' + el.className.trim().split(/\s+/).filter((c) => !c.startsWith('svelte-')).join('.') : '';
    return `<${el.tagName.toLowerCase()}${cls}> "${name.replace(/\s+/g, ' ').trim().slice(0, 50)}"`;
  };
  const visible = (el: Element) => {
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return false;
    if (el.closest('[inert], .visually-hidden')) return false;
    return el.checkVisibility({ opacityProperty: true, visibilityProperty: true });
  };

  const modal = [...document.querySelectorAll('dialog')].filter((d) => d.matches(':modal')).pop() ?? null;
  const scope: ParentNode = modal ?? document;

  // 1. no wrapping inside [data-nowrap]: all text of the element sits on one line box
  for (const el of scope.querySelectorAll<HTMLElement>('[data-nowrap]')) {
    if (!visible(el)) continue;
    const range = document.createRange();
    range.selectNodeContents(el);
    const rects = [...range.getClientRects()].filter((r) => r.width > 0.5 && r.height > 0.5).sort((a, b) => a.top - b.top);
    let lines = 0;
    let lineBottom = -Infinity;
    for (const r of rects) {
      if (r.top + r.height / 2 > lineBottom) {
        lines++;
        lineBottom = r.bottom;
      } else lineBottom = Math.max(lineBottom, r.bottom);
    }
    if (lines > 1) problems.push(`wraps onto ${lines} lines: ${label(el)}`);
    const cs = getComputedStyle(el);
    if (cs.display !== 'inline' && cs.display !== 'contents') {
      const ellipsis = cs.textOverflow === 'ellipsis' && cs.overflowX !== 'visible';
      if (!ellipsis && el.scrollWidth > el.clientWidth + 1) problems.push(`text sticks out of its box (${el.scrollWidth}>${el.clientWidth}): ${label(el)}`);
      const fs = parseFloat(cs.fontSize);
      if (ellipsis && el.scrollWidth > el.clientWidth + 1 && el.clientWidth < Math.min(el.scrollWidth, 2.5 * fs)) problems.push(`cut down to almost nothing (${el.clientWidth}px): ${label(el)}`);
    }
  }

  // 2. no horizontal scrolling, also inside an open sheet
  const de = document.documentElement;
  if (de.scrollWidth > de.clientWidth) problems.push(`page scrolls sideways: ${de.scrollWidth} > ${de.clientWidth}`);
  if (modal && modal.scrollWidth > modal.clientWidth + 1) problems.push(`sheet scrolls sideways: ${modal.scrollWidth} > ${modal.clientWidth}`);

  // 3. interactive targets: no overlap within a layer, big enough to tap
  const items = [...scope.querySelectorAll(INTERACTIVE)].filter(visible);
  const layerOf = (el: Element): Element => {
    for (let e: Element | null = el; e; e = e.parentElement) {
      if (e === modal || e.getAttribute('role') === 'menu') return e;
      const pos = getComputedStyle(e).position;
      if (pos === 'fixed' || pos === 'sticky') return e;
    }
    return document.body;
  };
  const sx = scrollX;
  const sy = scrollY;
  const boxes = items.map((el) => {
    const r = el.getBoundingClientRect();
    const layer = layerOf(el);
    const fixed = layer !== document.body && getComputedStyle(layer).position === 'fixed';
    return { el, layer, l: r.left + (fixed ? 0 : sx), t: r.top + (fixed ? 0 : sy), r: r.right + (fixed ? 0 : sx), b: r.bottom + (fixed ? 0 : sy), w: r.width, h: r.height };
  });
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      const a = boxes[i]!;
      const b = boxes[j]!;
      if (a.layer !== b.layer || a.el.contains(b.el) || b.el.contains(a.el)) continue;
      const w = Math.min(a.r, b.r) - Math.max(a.l, b.l);
      const h = Math.min(a.b, b.b) - Math.max(a.t, b.t);
      if (w > 1 && h > 1) problems.push(`overlap ${Math.round(w)}×${Math.round(h)}: ${label(a.el)} / ${label(b.el)}`);
    }
  }
  if (o.touchTargets) {
    for (const x of boxes) {
      if (x.w >= o.minTarget && x.h >= o.minTarget) continue;
      const el = x.el as HTMLElement;
      // inline links inside running text are exempt (WCAG 2.5.8)
      if (el.tagName === 'A' && getComputedStyle(el).display === 'inline') continue;
      const labels = 'labels' in el ? [...((el as HTMLInputElement).labels ?? [])] : [];
      if (labels.some((l) => l.contains(el) && l.getBoundingClientRect().width >= o.minTarget && l.getBoundingClientRect().height >= o.minTarget)) continue;
      problems.push(`small target ${Math.round(x.w)}×${Math.round(x.h)}: ${label(el)}`);
    }
  }
  return problems;
}

/** Fixed bars (footer, snackbar) must not cover controls once the page is scrolled to the end. */
function coveredAtEnd(): string[] {
  const problems: string[] = [];
  if ([...document.querySelectorAll('dialog')].some((d) => d.matches(':modal'))) return problems;
  scrollTo(0, document.documentElement.scrollHeight);
  const fixed = [...document.querySelectorAll<HTMLElement>('body *')].filter((e) => getComputedStyle(e).position === 'fixed' && e.getBoundingClientRect().height > 0);
  const bars = fixed.map((e) => [...e.children].map((c) => c.getBoundingClientRect()).filter((r) => r.width > 0 && r.height > 0)).flat();
  const controls = [...document.querySelectorAll('a[href], button, input, textarea, select')].filter((el) => !fixed.some((f) => f.contains(el)) && el.getBoundingClientRect().height > 0 && el.checkVisibility());
  for (const el of controls) {
    const r = el.getBoundingClientRect();
    if (r.bottom <= 0 || r.top >= innerHeight) continue;
    for (const b of bars) {
      const w = Math.min(r.right, b.right) - Math.max(r.left, b.left);
      const h = Math.min(r.bottom, b.bottom) - Math.max(r.top, b.top);
      if (w > 1 && h > 1) problems.push(`covered by a fixed bar at the end of the page: <${el.tagName.toLowerCase()}> "${((el as HTMLElement).innerText || el.getAttribute('aria-label') || '').trim().slice(0, 40)}"`);
    }
  }
  scrollTo(0, 0);
  return problems;
}

export async function setRootFont(page: Page, percent: number): Promise<void> {
  await page.evaluate((p) => (document.documentElement.style.fontSize = p === 100 ? '' : `${p}%`), percent);
  const px = await page.evaluate(() => parseFloat(getComputedStyle(document.body).fontSize));
  if (Math.abs(px - (16 * percent) / 100) > 0.5) throw new Error(`root font ${percent}% gave a body font of ${px}px: the CSS does not scale with the root size`);
}

export async function settle(page: Page): Promise<void> {
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));
}

export async function checkLayout(page: Page, o: LayoutOptions): Promise<string[]> {
  await page.evaluate(() => scrollTo(0, 0));
  await settle(page);
  const problems = await page.evaluate(inspect, o);
  problems.push(...(await page.evaluate(coveredAtEnd)));
  return problems;
}
