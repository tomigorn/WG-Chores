import { existsSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BIN, BUILD_HINT, FAKE_OIDC_BIN } from './server';

const dist = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../dist/index.html');

export default function globalSetup(): void {
  for (const bin of [BIN, FAKE_OIDC_BIN]) if (!existsSync(bin)) throw new Error(`${bin} missing; ${BUILD_HINT}`);
  if (existsSync(dist) && statSync(dist).mtimeMs > statSync(BIN).mtimeMs) throw new Error(`web/dist is newer than the server binary; ${BUILD_HINT}`);
}
