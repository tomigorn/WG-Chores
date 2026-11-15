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

  const textNodes = (el: Element): Text[] => {
    const out: Text[] = [];
    const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    for (let n = w.nextNode(); n; n = w.nextNode()) if ((n as Text).data.trim()) out.push(n as Text);
    return out;
  };
  const lineCount = (rects: DOMRect[]) => {
    let lines = 0;
    let lineBottom = -Infinity;
    for (const r of [...rects].sort((a, b) => a.top - b.top)) {
      if (r.top + r.height / 2 > lineBottom) {
        lines++;
        lineBottom = r.bottom;
      } else lineBottom = Math.max(lineBottom, r.bottom);
    }
    return lines;
  };
  const rangeRects = (node: Node, start?: number, end?: number) => {
    const range = document.createRange();
    if (start === undefined) range.selectNodeContents(node);
    else {
      range.setStart(node, start);
      range.setEnd(node, end!);
    }
    return [...range.getClientRects()].filter((r) => r.width > 0.5 && r.height > 0.5);
  };
  // characters drawn fully inside the element's box (clipped and ellipsized ones are outside or under the ellipsis)
  // the content box: border box minus border and padding; clipped text may only be drawn here
  const edges = (el: Element) => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    const px = (v: string) => parseFloat(v) || 0;
    const pad = {
      left: r.left + px(cs.borderLeftWidth),
      right: r.right - px(cs.borderRightWidth),
      top: r.top + px(cs.borderTopWidth),
      bottom: r.bottom - px(cs.borderBottomWidth),
    };
    const content = {
      left: pad.left + px(cs.paddingLeft),
      right: pad.right - px(cs.paddingRight),
      top: pad.top + px(cs.paddingTop),
      bottom: pad.bottom - px(cs.paddingBottom),
    };
    return { pad, content };
  };
  const inside = (r: DOMRect, b: { left: number; right: number; top: number; bottom: number }) =>
    r.left >= b.left - 0.5 && r.right <= b.right + 0.5 && r.top >= b.top - 0.5 && r.bottom <= b.bottom + 0.5;
  const overlaps = (r: DOMRect, b: { left: number; right: number; top: number; bottom: number }) =>
    Math.min(r.right, b.right) - Math.max(r.left, b.left) > 1 && Math.min(r.bottom, b.bottom) - Math.max(r.top, b.top) > 1;
  const shownChars = (el: Element) => {
    const { pad, content } = edges(el);
    let total = 0;
    let shown = 0;
    let leaked = 0;
    const tops: DOMRect[] = [];
    for (const t of textNodes(el)) {
      for (let i = 0; i < t.data.length; i++) {
        if (/\s/.test(t.data[i]!)) continue;
        total++;
        const r = rangeRects(t, i, i + 1)[0];
        if (!r) continue;
        if (inside(r, content)) {
          shown++;
          tops.push(r);
        } else if (overlaps(r, pad)) {
          // painted in the top or bottom padding band (an ellipsis hides what crosses the side edges)
          const band = (lo: number, hi: number) => Math.min(r.bottom, hi) - Math.max(r.top, lo) > 1;
          if (band(pad.top, content.top) || band(content.bottom, pad.bottom)) leaked++;
        }
      }
    }
    return { total, shown, leaked, lines: lineCount(tops) };
  };
  // words split over two line boxes; a break after a hyphen is a normal break
  const splitWords = (el: Element) => {
    const out: string[] = [];
    for (const t of textNodes(el)) {
      for (const m of t.data.matchAll(/[^\s\-–\u00ad]+[-–\u00ad]?/g)) {
        // compare the first and the last letter: a soft hyphen's glyph is reported in both neighbouring ranges
        const letters = m[0].replace(/[-–\u00ad]$/, '');
        const first = rangeRects(t, m.index!, m.index! + 1).sort((x, y) => y.top - x.top)[0];
        const last = rangeRects(t, m.index! + letters.length - 1, m.index! + letters.length).sort((x, y) => x.top - y.top)[0];
        if (first && last && last.top > first.top + first.height / 2) out.push(letters);
      }
    }
    return out;
  };
  const MIN_SHOWN = 8;
  const checkShown = (el: Element, maxLines: number) => {
    const c = shownChars(el);
    // one shown character is lost to the ellipsis
    if (c.shown < c.total && c.shown - 1 < Math.min(MIN_SHOWN, c.total)) problems.push(`shows only ${Math.max(0, c.shown - 1)} of ${c.total} characters: ${label(el)}`);
    if (c.lines > maxLines) problems.push(`shows ${c.lines} lines, at most ${maxLines}: ${label(el)}`);
    if (c.leaked) problems.push(`${c.leaked} characters drawn in the padding: ${label(el)}`);
  };

  // 1. [data-nowrap] (values, dates, short names): one line box; a cut name still shows at least 8 characters
  for (const el of scope.querySelectorAll<HTMLElement>('[data-nowrap]')) {
    if (!visible(el)) continue;
    const lines = lineCount(rangeRects(el));
    if (lines > 1) problems.push(`wraps onto ${lines} lines: ${label(el)}`);
    const cs = getComputedStyle(el);
    if (cs.display !== 'inline' && cs.display !== 'contents') {
      const ellipsis = cs.textOverflow === 'ellipsis' && cs.overflowX !== 'visible';
      if (!ellipsis) {
        const { content } = edges(el);
        const right = Math.max(0, ...rangeRects(el).map((r) => r.right));
        if (el.scrollWidth > el.clientWidth + 0.5 || right > content.right + 0.5) problems.push(`text sticks out of its box (${el.scrollWidth}>${el.clientWidth}): ${label(el)}`);
      }
      if (ellipsis) checkShown(el, 1);
    }
  }

  // 1b. [data-clamp] (names and titles that may take a second line): whole words only, at most the clamp, at least 8 characters shown
  for (const el of scope.querySelectorAll<HTMLElement>('[data-clamp]')) {
    if (!visible(el)) continue;
    const max = Number(el.dataset.clamp) || 2;
    checkShown(el, max);
    const width = el.getBoundingClientRect().width;
    for (const w of splitWords(el)) {
      const t = textNodes(el).find((n) => n.data.includes(w))!;
      const i = t.data.indexOf(w);
      // a single word wider than the line may break; anything else is a mid-word break
      const wordWidth = rangeRects(t, i, i + w.length).reduce((sum, r) => sum + r.width, 0);
      if (wordWidth <= width) problems.push(`word "${w}" broken across lines: ${label(el)}`);
    }
  }

  // 1b'. any other element that clips its text (ellipsis or line clamp) must not draw it in its padding
  for (const el of scope.querySelectorAll<HTMLElement>('body *')) {
    if (el.matches('[data-clamp], [data-nowrap]') || !visible(el)) continue;
    const cs = getComputedStyle(el);
    const clips = cs.overflowX !== 'visible' || cs.overflowY !== 'visible';
    const clampsText = cs.textOverflow === 'ellipsis' || (cs.webkitLineClamp && cs.webkitLineClamp !== 'none');
    if (!clips || !clampsText || !textNodes(el).length) continue;
    const c = shownChars(el);
    if (c.leaked) problems.push(`${c.leaked} characters drawn in the padding: ${label(el)}`);
  }

  // 1c. [data-copy] (UI text: banner, empty states, hints): never a word broken across lines
  for (const el of scope.querySelectorAll<HTMLElement>('[data-copy]')) {
    if (!visible(el)) continue;
    for (const w of splitWords(el)) problems.push(`word "${w}" broken across lines: ${label(el)}`);
  }

  // character boxes actually painted: not clipped away by the element or an ancestor up to it
  const drawnChars = (root: Element) => {
    const out: DOMRect[] = [];
    for (const t of textNodes(root)) {
      const clips: DOMRect[] = [];
      for (let e = t.parentElement; e; e = e.parentElement) {
        if (getComputedStyle(e).overflowX !== 'visible' || getComputedStyle(e).overflowY !== 'visible') clips.push(e.getBoundingClientRect());
        if (e === root) break;
      }
      for (let i = 0; i < t.data.length; i++) {
        if (/\s/.test(t.data[i]!)) continue;
        const r = rangeRects(t, i, i + 1)[0];
        if (r && clips.every((c) => r.left >= c.left - 0.5 && r.right <= c.right + 0.5 && r.top >= c.top - 0.5 && r.bottom <= c.bottom + 0.5)) out.push(r);
      }
    }
    return out;
  };
  // 1d. a [data-nowrap] text must not run into a sibling in its row (a date touching the name)
  for (const el of scope.querySelectorAll<HTMLElement>('[data-nowrap]')) {
    if (!visible(el)) continue;
    const mine = rangeRects(el);
    for (const sib of el.parentElement?.children ?? []) {
      if (sib === el || !visible(sib)) continue;
      const theirs = drawnChars(sib);
      if (mine.some((r) => theirs.some((t) => overlaps(r, t)))) problems.push(`text runs into its sibling: ${label(el)} / ${label(sib)}`);
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
      // a control drawn on top of a larger one that fully holds it (the tick on a row link) is nested in effect
      const [small, big] = a.w * a.h <= b.w * b.h ? [a, b] : [b, a];
      if (small.l >= big.l - 0.5 && small.r <= big.r + 0.5 && small.t >= big.t - 0.5 && small.b <= big.b + 0.5) {
        small.el.scrollIntoView({ block: 'center', behavior: 'instant' });
        const r = small.el.getBoundingClientRect();
        const stack = document.elementsFromPoint((r.left + r.right) / 2, (r.top + r.bottom) / 2);
        scrollTo(sx, sy);
        const onTop = stack.findIndex((e) => small.el.contains(e));
        const under = stack.findIndex((e) => big.el.contains(e) && !small.el.contains(e));
        if (onTop >= 0 && (under < 0 || onTop < under)) continue;
      }
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

/** Runs in the page: characters of the element drawn fully inside its box, and on how many lines. */
export function shownText(el: Element): { total: number; shown: number; lines: number; text: string } {
  const r0 = el.getBoundingClientRect();
  const cs = getComputedStyle(el);
  const px = (v: string) => parseFloat(v) || 0;
  // the content box: text drawn in the padding does not count as shown
  const box = {
    left: r0.left + px(cs.borderLeftWidth) + px(cs.paddingLeft),
    right: r0.right - px(cs.borderRightWidth) - px(cs.paddingRight),
    top: r0.top + px(cs.borderTopWidth) + px(cs.paddingTop),
    bottom: r0.bottom - px(cs.borderBottomWidth) - px(cs.paddingBottom),
  };
  const tops = new Set<number>();
  let total = 0;
  let shown = 0;
  let text = '';
  const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  for (let n = w.nextNode() as Text | null; n; n = w.nextNode() as Text | null) {
    for (let i = 0; i < n.data.length; i++) {
      const ch = n.data[i]!;
      if (/\s/.test(ch)) continue;
      total++;
      const range = document.createRange();
      range.setStart(n, i);
      range.setEnd(n, i + 1);
      const r = range.getBoundingClientRect();
      if (r.width > 0 && r.left >= box.left - 0.5 && r.right <= box.right + 0.5 && r.top >= box.top - 0.5 && r.bottom <= box.bottom + 0.5) {
        shown++;
        text += ch;
        tops.add(Math.round(r.top / 4));
      }
    }
  }
  return { total, shown, lines: tops.size, text };
}
