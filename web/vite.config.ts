import { defineConfig } from 'vitest/config';
import { svelte } from '@sveltejs/vite-plugin-svelte';

const server = process.env.WGC_SERVER ?? 'http://localhost:8080';

const proxy = {
  target: server,
  changeOrigin: true,
  configure: (p: { on: (ev: 'proxyReq', cb: (req: { setHeader: (k: string, v: string) => void }) => void) => void }) => {
    // the server's CSRF check compares Origin with PUBLIC_URL
    p.on('proxyReq', (req) => req.setHeader('origin', new URL(server).origin));
  },
};

export default defineConfig({
  plugins: [svelte()],
  build: { outDir: 'dist', emptyOutDir: true, target: 'es2022', assetsInlineLimit: 0, modulePreload: { polyfill: false } },
  server: { proxy: { '/api': proxy, '/auth': proxy } },
  test: { environment: 'node', include: ['src/**/*.test.ts'] },
});
