// Renders the app icons from one SVG mark: NODE_PATH=<dir with playwright> node scripts/icons.mjs
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');

const out = join(dirname(fileURLToPath(import.meta.url)), '../public/icons');
const ACCENT = '#2a55c5';
const MARK = '<path d="M13 5h8"/><path d="M13 12h8"/><path d="M13 19h8"/><path d="m3 17 2 2 4-4"/><path d="m3 7 2 2 4-4"/>';

// scale: share of the canvas the 24px mark covers; radius: corner radius as share of the size
function svg({ size, bg, fg, scale, radius }) {
  const m = size * scale;
  const off = (size - m) / 2;
  const rect = bg ? `<rect width="${size}" height="${size}" rx="${size * radius}" fill="${bg}"/>` : '';
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">${rect}<g transform="translate(${off} ${off}) scale(${m / 24})" fill="none" stroke="${fg}" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round">${MARK}</g></svg>`;
}

const ICONS = [
  ['icon-192.png', { size: 192, bg: ACCENT, fg: '#fff', scale: 0.56, radius: 0.22 }],
  ['icon-512.png', { size: 512, bg: ACCENT, fg: '#fff', scale: 0.56, radius: 0.22 }],
  ['maskable-512.png', { size: 512, bg: ACCENT, fg: '#fff', scale: 0.46, radius: 0 }],
  ['apple-touch-icon-180.png', { size: 180, bg: ACCENT, fg: '#fff', scale: 0.56, radius: 0 }],
  ['badge-96.png', { size: 96, bg: null, fg: '#fff', scale: 0.8, radius: 0 }],
];

mkdirSync(out, { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage();
for (const [name, o] of ICONS) {
  await page.setViewportSize({ width: o.size, height: o.size });
  await page.setContent(`<style>html,body{margin:0;background:transparent}svg{display:block}</style>${svg(o)}`);
  await page.locator('svg').screenshot({ path: join(out, name), omitBackground: true });
  console.log(`wrote public/icons/${name}`);
}
await browser.close();
