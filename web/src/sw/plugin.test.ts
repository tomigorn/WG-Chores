import { describe, expect, it } from 'vitest';
import { serviceWorker } from '../../sw-plugin';

type Hook = { handler: (this: unknown, o: unknown, b: Record<string, unknown>) => void };

function run(sw: { imports?: string[]; dynamicImports?: string[] }) {
  const p = serviceWorker();
  const bundle: Record<string, unknown> = {
    'sw.js': { type: 'chunk', code: "var b=JSON.parse('__WGC_BUILD__')", imports: [], dynamicImports: [], ...sw },
    'assets/index-abc.js': { type: 'chunk', code: 'x', imports: [], dynamicImports: [] },
    'index.html': { type: 'asset', source: '<html>' },
  };
  const ctx = {
    error: (m: string) => {
      throw new Error(m);
    },
  };
  (p.generateBundle as unknown as Hook).handler.call(ctx, {}, bundle);
  return (bundle['sw.js'] as { code: string }).code;
}

describe('service worker build', () => {
  it('injects_precache_list', () => {
    const code = run({});
    const build = JSON.parse(JSON.parse(code.slice(code.indexOf('(') + 1, code.lastIndexOf(')')))) as { version: string; files: string[] };
    expect(build.files).toEqual(['/assets/index-abc.js', '/index.html']);
    expect(build.version).toMatch(/^[0-9a-f]{16}$/);
  });

  it('sw_chunk_must_be_self_contained', () => {
    expect(() => run({ imports: ['assets/shared-1.js'] })).toThrow(/self-contained/);
    expect(() => run({ dynamicImports: ['assets/lazy-1.js'] })).toThrow(/self-contained/);
  });
});
