import { execFileSync, spawn, type ChildProcess } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, openSync, closeSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
export const BIN = process.env.WGC_BIN ?? path.join(ROOT, 'server/target/debug/wg-chores');
export const FAKE_OIDC_BIN = process.env.WGC_FAKE_OIDC_BIN ?? path.join(ROOT, 'server/target/debug/fake-oidc');
/** Test output (results, server data, layout screenshots) */
export const OUT = path.resolve(process.env.E2E_OUT ?? path.join(ROOT, 'web/e2e-out'));
export const WORK = path.join(OUT, 'servers');
export const BUILD_HINT = 'build first: cd web && npm run e2e:build';

export function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.unref();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => {
      const port = (s.address() as net.AddressInfo).port;
      s.close(() => resolve(port));
    });
  });
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function exited(p: ChildProcess): Promise<void> {
  return p.exitCode !== null || p.signalCode !== null ? Promise.resolve() : new Promise((r) => p.once('exit', () => r()));
}

async function waitFor(url: string, proc: ChildProcess, log: string, timeout = 20_000): Promise<void> {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    if (proc.exitCode !== null) throw new Error(`process exited (${proc.exitCode}):\n${readFileSync(log, 'utf8')}`);
    try {
      if ((await fetch(url)).ok) return;
    } catch {
      /* not up yet */
    }
    await sleep(100);
  }
  throw new Error(`timeout waiting for ${url}:\n${readFileSync(log, 'utf8')}`);
}

/** One wg-chores server with its own temporary DATA_DIR; stop/start keeps port and data. */
export class Server {
  proc: ChildProcess | null = null;
  readonly url: string;
  readonly log: string;

  private constructor(
    readonly port: number,
    readonly dataDir: string,
    readonly env: Record<string, string>,
  ) {
    this.url = `http://localhost:${port}`;
    this.log = path.join(dataDir, 'server.log');
  }

  static async start(env: Record<string, string> = {}): Promise<Server> {
    if (!existsSync(BIN)) throw new Error(`${BIN} missing; ${BUILD_HINT}`);
    mkdirSync(WORK, { recursive: true });
    const s = new Server(await freePort(), mkdtempSync(path.join(WORK, 'srv-')), env);
    await s.startAgain();
    return s;
  }

  async startAgain(): Promise<void> {
    const out = openSync(this.log, 'a');
    this.proc = spawn(BIN, ['serve'], {
      env: {
        PATH: process.env.PATH ?? '',
        TZ: 'UTC',
        PUBLIC_URL: this.url,
        LISTEN: `127.0.0.1:${this.port}`,
        DATA_DIR: this.dataDir,
        OPEN_REGISTRATION: 'true',
        ...this.env,
      },
      stdio: ['ignore', out, out],
    });
    closeSync(out);
    await waitFor(`${this.url}/healthz`, this.proc, this.log);
  }

  /** A local account made with the admin command, e.g. while OPEN_REGISTRATION is off. */
  createUser(username: string, displayName: string, password: string): void {
    const file = path.join(this.dataDir, `pw-${username}`);
    writeFileSync(file, password, { mode: 0o600 });
    execFileSync(BIN, ['create-user', username, '--display-name', displayName, '--password-file', file], {
      env: { PATH: process.env.PATH ?? '', TZ: 'UTC', DATA_DIR: this.dataDir },
      stdio: 'pipe',
    });
    rmSync(file);
  }

  async stop(signal: NodeJS.Signals = 'SIGTERM'): Promise<void> {
    const p = this.proc;
    if (!p) return;
    this.proc = null;
    p.kill(signal);
    await exited(p);
  }

  async dispose(): Promise<void> {
    await this.stop('SIGKILL');
    rmSync(this.dataDir, { recursive: true, force: true });
  }
}

export interface FakeUser {
  sub: string;
  name: string | null;
  preferred_username: string | null;
  picture: string | null;
  email: string | null;
  groups: string[] | null;
}

/** The test-only OpenID provider (`fake-oidc` binary, cargo feature test-util). */
export class FakeOidc {
  private constructor(
    readonly proc: ChildProcess,
    readonly env: Record<string, string>,
  ) {}

  get issuer(): string {
    return this.env.OIDC_ISSUER!;
  }

  static async start(): Promise<FakeOidc> {
    if (!existsSync(FAKE_OIDC_BIN)) throw new Error(`${FAKE_OIDC_BIN} missing; ${BUILD_HINT}`);
    const port = await freePort();
    const proc = spawn(FAKE_OIDC_BIN, [String(port)], { stdio: ['ignore', 'pipe', 'inherit'] });
    const line = await new Promise<string>((resolve, reject) => {
      let buf = '';
      const timer = setTimeout(() => reject(new Error('fake-oidc did not start')), 15_000);
      proc.stdout!.on('data', (d: Buffer) => {
        buf += d.toString();
        const nl = buf.indexOf('\n');
        if (nl >= 0) {
          clearTimeout(timer);
          resolve(buf.slice(0, nl));
        }
      });
      proc.once('exit', (c) => reject(new Error(`fake-oidc exited ${c}`)));
    });
    const env = Object.fromEntries(line.trim().split(/\s+/).map((kv) => kv.split('=', 2) as [string, string]));
    await waitFor(`${env.OIDC_ISSUER}/.well-known/openid-configuration`, proc, '/dev/null');
    return new FakeOidc(proc, env);
  }

  async setUser(u: Partial<FakeUser> & { sub: string }): Promise<void> {
    const body: FakeUser = { name: 'Jane Doe', preferred_username: null, picture: null, email: null, groups: ['WG'], ...u };
    const r = await fetch(`${this.issuer}/_test/user`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
    if (!r.ok) throw new Error(`fake-oidc set user: ${r.status}`);
  }

  async stop(): Promise<void> {
    this.proc.kill('SIGKILL');
    await exited(this.proc);
  }
}
