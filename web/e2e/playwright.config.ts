import { defineConfig, devices } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';

const out = path.join(os.homedir(), '.cache/claude-work/wgc-e2e/results');

// WebKit is not available on this host: iPhone and Pixel are Chromium with device emulation
export default defineConfig({
  testDir: '.',
  outputDir: out,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: Number(process.env.E2E_WORKERS ?? 3),
  retries: 0,
  reporter: [['list']],
  globalSetup: './support/global-setup.ts',
  use: {
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    actionTimeout: 10_000,
    locale: 'en-GB',
    timezoneId: 'UTC',
    serviceWorkers: 'allow',
  },
  projects: [
    { name: 'desktop', testIgnore: /layout\.spec/, use: { ...devices['Desktop Chrome'] } },
    { name: 'iphone', testIgnore: /layout\.spec/, use: { ...devices['iPhone 13'], browserName: 'chromium' } },
    { name: 'pixel', testIgnore: /layout\.spec/, use: { ...devices['Pixel 7'] } },
    { name: 'layout', testMatch: /layout\.spec/, use: { ...devices['Desktop Chrome'] } },
  ],
});
