// Play Store icon and feature graphic from the app mark: NODE_PATH=<dir with playwright> node android/store/graphics.mjs [roboto-600.woff2]
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');

const out = dirname(fileURLToPath(import.meta.url));
const font = process.argv[2] ? `@font-face{font-family:Roboto;font-weight:600;src:url(data:font/woff2;base64,${readFileSync(process.argv[2]).toString('base64')})}` : '';
// same mark and accent as web/scripts/icons.mjs
const ACCENT = '#2a55c5';
const MARK = '<path d="M13 5h8"/><path d="M13 12h8"/><path d="M13 19h8"/><path d="m3 17 2 2 4-4"/><path d="m3 7 2 2 4-4"/>';

function mark(size, scale, fg, bg, radius) {
  const m = size * scale;
  const off = (size - m) / 2;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}"><rect width="${size}" height="${size}" rx="${size * radius}" fill="${bg}"/><g transform="translate(${off} ${off}) scale(${m / 24})" fill="none" stroke="${fg}" stroke-width="2.25" stroke-linecap="round" stroke-linejoin="round">${MARK}</g></svg>`;
}

const pages = [
  ['icon-512.png', 512, 512, mark(512, 0.5, '#fff', ACCENT, 0)],
  ['feature-graphic.png', 1024, 500,
    `<div style="width:1024px;height:500px;background:${ACCENT};display:flex;align-items:center;justify-content:center;gap:44px">${mark(168, 0.56, ACCENT, '#fff', 0.22)}` +
    `<span style="font:600 92px Roboto,system-ui,sans-serif;color:#fff;letter-spacing:-0.5px">WG Chores</span></div>`],
];

const browser = await chromium.launch();
const page = await browser.newPage();
for (const [name, width, height, html] of pages) {
  await page.setViewportSize({ width, height });
  await page.setContent(`<style>${font}html,body{margin:0}svg{display:block}</style>${html}`);
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: join(out, name), clip: { x: 0, y: 0, width, height } });
  console.log(`wrote android/store/${name}`);
}
await browser.close();
