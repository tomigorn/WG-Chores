import { createHash } from 'node:crypto';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import type { Plugin } from 'vite';

function walk(dir: string): string[] {
  let out: string[] = [];
  for (const n of readdirSync(dir)) {
    const p = join(dir, n);
    out = statSync(p).isDirectory() ? out.concat(walk(p)) : out.concat(p);
  }
  return out;
}

/** Builds src/sw.ts as /sw.js (no hash) and injects the precache list and a content version. */
export function serviceWorker(): Plugin {
  let publicDir = '';
  return {
    name: 'wgc-service-worker',
    apply: 'build',
    configResolved(c) {
      publicDir = c.publicDir;
    },
    buildStart() {
      this.emitFile({ type: 'chunk', id: resolve(import.meta.dirname, 'src/sw.ts'), fileName: 'sw.js' });
    },
    generateBundle: {
      order: 'post',
      handler(_, bundle) {
        const entries: [string, string | Uint8Array][] = [];
        for (const [name, f] of Object.entries(bundle)) {
          if (name === 'sw.js' || name.endsWith('.map')) continue;
          entries.push([name, f.type === 'chunk' ? f.code : f.source]);
        }
        if (publicDir) for (const p of walk(publicDir)) entries.push([relative(publicDir, p).split('\\').join('/'), readFileSync(p)]);
        entries.sort(([a], [b]) => a.localeCompare(b));
        const h = createHash('sha256');
        for (const [n, c] of entries) h.update(n).update('\0').update(c).update('\0');
        const build = { version: h.digest('hex').slice(0, 16), files: entries.map(([n]) => '/' + n) };
        const sw = bundle['sw.js'];
        const placeholder = /(["'`])__WGC_BUILD__\1/;
        if (!sw || sw.type !== 'chunk' || !placeholder.test(sw.code)) return this.error('sw.js build placeholder missing');
        sw.code = sw.code.replace(placeholder, () => JSON.stringify(JSON.stringify(build)));
      },
    },
  };
}
