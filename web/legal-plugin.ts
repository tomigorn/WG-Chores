import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Plugin } from 'vite';
import { legalPage } from './src/lib/markdown';

const PAGES: [string, string][] = [
  ['privacy', 'privacy.md'],
  ['account-deletion', 'account-deletion.md'],
];

/** Renders docs/privacy.md and docs/account-deletion.md as static pages at /privacy and /account-deletion. */
export function legalPages(): Plugin {
  return {
    name: 'wgc-legal-pages',
    apply: 'build',
    generateBundle(_, bundle) {
      const css = Object.keys(bundle).find((n) => n.endsWith('.css'));
      if (!css) return this.error('legal pages: no stylesheet in the bundle');
      for (const [dir, doc] of PAGES) {
        const md = readFileSync(resolve(import.meta.dirname, '../docs', doc), 'utf8');
        this.emitFile({ type: 'asset', fileName: `${dir}/index.html`, source: legalPage(md, '/' + css) });
      }
    },
  };
}
